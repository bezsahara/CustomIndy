package org.bezsahara.customindy.ir

import org.bezsahara.customindy.annotations.MHKind
import org.bezsahara.customindy.codegen.IndyJvmIntrinsicExtension
import org.bezsahara.customindy.shared.IndyBootstrapArg
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.bezsahara.customindy.shared.IndyLog
import org.bezsahara.customindy.shared.findClassCompat
import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrIntrinsicExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Opcodes

class IndyIrGenerationExtension(
    private val registry: IndyCallSiteRegistry,
) : IrGenerationExtension {
    @Volatile
    private var jvmIndySymbols: JvmIndySymbols? = null

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val symbols = buildJvmIndySymbols(pluginContext)
        jvmIndySymbols = symbols
        val transformer = IndyCallTransformer(pluginContext, registry, symbols)
        moduleFragment.transform(transformer, null)
    }

    override fun getPlatformIntrinsicExtension(loweringContext: LoweringContext): IrIntrinsicExtension? {
        val jvmContext = loweringContext as? JvmBackendContext ?: return null
        if (jvmContext.irPluginContext == null) return null
        val symbols = jvmIndySymbols ?: return null
        return IndyJvmIntrinsicExtension(symbols)
    }
}

private class IndyCallTransformer(
    private val pluginContext: IrPluginContext,
    private val registry: IndyCallSiteRegistry,
    private val symbols: JvmIndySymbols,
) : IrElementTransformerVoid() {
    private var currentFilePath: String? = null

    override fun visitFile(declaration: IrFile): IrFile {
        val prev = currentFilePath
        currentFilePath = declaration.fileEntry.name
        val result = super.visitFile(declaration)
        currentFilePath = prev
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val call = super.visitCall(expression) as IrCall
        val callee = call.symbol.owner as? IrSimpleFunction ?: return call

        val customIndy = callee.extractCustomIndyInfo() ?: return call
        if (callee.dispatchReceiverParameter != null) {
            return call
        }

        val filePath = currentFilePath ?: return call
        if (call.startOffset < 0 || call.endOffset < 0) return call

        val siteInfo = registry.find(filePath, call.startOffset, call.endOffset)
        if (siteInfo == null) {
            IndyLog.warn {
                "No call-site data for @CustomIndy call at $filePath [${call.startOffset}, ${call.endOffset}]; " +
                        "using empty bootstrap args"
            }
        }
//        val bsmArgs = siteInfo
//            ?.args
//            ?.mapNotNull { it.toIrArg(pluginContext) }
//            ?.sortedBy { it.index }
//            ?: emptyList()

        val bsmArgs = siteInfo?.args?.mapNotNull { it.toIrArg(pluginContext) }?.sortedBy { it.index } ?: emptyList()

        IndyLog.warn {
            "Rewriting @CustomIndy call at $filePath [${call.startOffset}, ${call.endOffset}] " +
                    "bsmArgs=${bsmArgs.size}"
        }

        val bsmHandleCall = buildJvmMethodHandleCall(
            call.startOffset,
            call.endOffset,
            pluginContext,
            symbols,
            customIndy.handle
        )
        val bsmArgExpressions = bsmArgs.mapNotNull {
            it.toIrExpression(pluginContext, symbols, call.startOffset, call.endOffset)
        }
        val bsmVararg = IrVarargImpl(
            call.startOffset,
            call.endOffset,
            symbols.arrayOfAnyType,
            pluginContext.irBuiltIns.anyType,
            bsmArgExpressions
        )

        val indyCall = IrCallImpl.fromSymbolOwner(call.startOffset, call.endOffset, call.type, symbols.jvmIndy).apply {
            if (typeArguments.isNotEmpty()) {
                typeArguments[0] = call.type
            }
            arguments[0] = call
            arguments[1] = bsmHandleCall
            arguments[2] = bsmVararg
        }

        indyCall.indyCallData = IndyCallData(
            targetSymbol = call.symbol,
            dynamicCall = call,
            bootstrapHandle = customIndy.handle,
            bootstrapArgs = bsmArgs,
        )

        return indyCall
    }
}

private data class CustomIndyInfo(
    val handle: Handle,
)

private fun IrSimpleFunction.extractCustomIndyInfo(): CustomIndyInfo? {
    val annotation = getAnnotation(org.bezsahara.customindy.shared.IndyFqNames.CUSTOM_INDY) ?: return null

    fun argByName(name: String): IrExpression? {
        val params = annotation.symbol.owner.parameters
        val param = params.firstOrNull { it.name.asString() == name } ?: return null
        return annotation.arguments[param.indexInParameters]
    }

    val kindExpr = argByName("bsmKind") as? IrGetEnumValue ?: return null
    val kindName = kindExpr.symbol.owner.name.asString()
    val kind = MHKind.valueOf(kindName)

    val ownerExpr = argByName("bsmOwner") as? IrClassReference ?: return null
    val ownerClass = (ownerExpr.symbol as? IrClassSymbol)?.owner ?: return null
    val ownerInternalName = irClassInternalName(ownerClass)

    val nameExpr = argByName("bsmName") as? IrConst ?: return null
    val descExpr = argByName("bsmDesc") as? IrConst ?: return null

    val bsmName = nameExpr.value as? String ?: return null
    val bsmDesc = descExpr.value as? String ?: return null

    val isInterface = ownerClass.kind == ClassKind.INTERFACE

    return CustomIndyInfo(
        handle = Handle(kind.tag, ownerInternalName, bsmName, bsmDesc, isInterface),
    )
}

private fun IndyBootstrapArg.toIrArg(pluginContext: IrPluginContext): IndyBootstrapIrArg? {
    return when (this) {
        is IndyBootstrapArg.IntArg -> IndyBootstrapIrArg.IntArg(index, value)
        is IndyBootstrapArg.LongArg -> IndyBootstrapIrArg.LongArg(index, value)
        is IndyBootstrapArg.DoubleArg -> IndyBootstrapIrArg.DoubleArg(index, value)
        is IndyBootstrapArg.StringArg -> IndyBootstrapIrArg.StringArg(index, value)
        is IndyBootstrapArg.ClassArg -> {
            val symbol = resolveClassSymbol(pluginContext, classId) ?: return null
            IndyBootstrapIrArg.ClassArg(index, symbol.defaultType)
        }
        is IndyBootstrapArg.MethodHandleArg -> {
            val symbol = resolveClassSymbol(pluginContext, ownerClassId) ?: return null
            val isInterface = symbol.owner.kind == ClassKind.INTERFACE
            IndyBootstrapIrArg.MethodHandleArg(index, symbol.defaultType, name, desc, Opcodes.H_INVOKESTATIC, isInterface)
        }
        is IndyBootstrapArg.MethodTypeArg -> IndyBootstrapIrArg.MethodTypeArg(index, desc)
    }
}

@OptIn(ObsoleteDescriptorBasedAPI::class)
private fun buildJvmIndySymbols(pluginContext: IrPluginContext): JvmIndySymbols {
    val kotlinJvmInternalPackage = createEmptyExternalPackageFragment(
        pluginContext.moduleDescriptor,
        FqName("kotlin.jvm.internal")
    )
    val arrayOfAnyType = pluginContext.irBuiltIns.arrayClass.typeWith(pluginContext.irBuiltIns.anyType)

    val jvmIndy = pluginContext.irFactory.buildFun {
        name = Name.special("<jvm-indy>")
        origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.IR_BUILTINS_STUB
    }.apply {
        parent = kotlinJvmInternalPackage
        // Use a non-standard type parameter name to avoid matching the built-in JVM intrinsic key.
        val t = addTypeParameter("T0", pluginContext.irBuiltIns.anyNType)
        addValueParameter("dynamicCall", t.defaultType)
        addValueParameter("bootstrapMethodHandle", pluginContext.irBuiltIns.anyType)
        addValueParameter {
            name = Name.identifier("bootstrapMethodArguments")
            type = arrayOfAnyType
            varargElementType = pluginContext.irBuiltIns.anyType
        }
        returnType = t.defaultType
    }.symbol

    val jvmMethodType = pluginContext.irFactory.buildFun {
        name = Name.special("<jvm-method-type>")
        origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.IR_BUILTINS_STUB
    }.apply {
        parent = kotlinJvmInternalPackage
        returnType = pluginContext.irBuiltIns.anyType
        addValueParameter("descriptor", pluginContext.irBuiltIns.stringType)
    }.symbol

    val jvmMethodHandle = pluginContext.irFactory.buildFun {
        name = Name.special("<jvm-method-handle>")
        origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.IR_BUILTINS_STUB
    }.apply {
        parent = kotlinJvmInternalPackage
        returnType = pluginContext.irBuiltIns.anyType
        addValueParameter("tag", pluginContext.irBuiltIns.intType)
        addValueParameter("owner", pluginContext.irBuiltIns.stringType)
        addValueParameter("name", pluginContext.irBuiltIns.stringType)
        addValueParameter("descriptor", pluginContext.irBuiltIns.stringType)
        addValueParameter("isInterface", pluginContext.irBuiltIns.booleanType)
    }.symbol

    return JvmIndySymbols(
        jvmIndy = jvmIndy,
        jvmMethodHandle = jvmMethodHandle,
        jvmMethodType = jvmMethodType,
        arrayOfAnyType = arrayOfAnyType,
    )
}

private fun buildJvmMethodHandleCall(
    startOffset: Int,
    endOffset: Int,
    pluginContext: IrPluginContext,
    symbols: JvmIndySymbols,
    handle: Handle,
): IrCall {
    val irBuiltIns = pluginContext.irBuiltIns
    return IrCallImpl.fromSymbolOwner(startOffset, endOffset, irBuiltIns.anyType, symbols.jvmMethodHandle).apply {
        arguments[0] = IrConstImpl.int(startOffset, endOffset, irBuiltIns.intType, handle.tag)
        arguments[1] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, handle.owner)
        arguments[2] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, handle.name)
        arguments[3] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, handle.desc)
        arguments[4] = IrConstImpl.boolean(startOffset, endOffset, irBuiltIns.booleanType, handle.isInterface)
    }
}

private fun IndyBootstrapIrArg.toIrExpression(
    pluginContext: IrPluginContext,
    symbols: JvmIndySymbols,
    startOffset: Int,
    endOffset: Int,
): IrExpression? {
    val irBuiltIns = pluginContext.irBuiltIns
    return when (this) {
        is IndyBootstrapIrArg.IntArg ->
            IrConstImpl.int(startOffset, endOffset, irBuiltIns.intType, value)
        is IndyBootstrapIrArg.LongArg ->
            IrConstImpl.long(startOffset, endOffset, irBuiltIns.longType, value)
        is IndyBootstrapIrArg.DoubleArg ->
            IrConstImpl.double(startOffset, endOffset, irBuiltIns.doubleType, value)
        is IndyBootstrapIrArg.StringArg ->
            IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, value)
        is IndyBootstrapIrArg.ClassArg -> {
            val classifier = type.classifierOrNull ?: return null
            val kClassType = irBuiltIns.kClassClass.typeWith(type)
            IrClassReferenceImpl(startOffset, endOffset, kClassType, classifier, type)
        }
        is IndyBootstrapIrArg.MethodTypeArg ->
            IrCallImpl.fromSymbolOwner(startOffset, endOffset, irBuiltIns.anyType, symbols.jvmMethodType).apply {
                arguments[0] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, desc)
            }
        is IndyBootstrapIrArg.MethodHandleArg -> {
            val ownerClass = ownerType.classOrNull?.owner ?: return null
            val ownerInternalName = irClassInternalName(ownerClass)
            IrCallImpl.fromSymbolOwner(startOffset, endOffset, irBuiltIns.anyType, symbols.jvmMethodHandle).apply {
                arguments[0] = IrConstImpl.int(startOffset, endOffset, irBuiltIns.intType, tag)
                arguments[1] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, ownerInternalName)
                arguments[2] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, name)
                arguments[3] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, desc)
                arguments[4] = IrConstImpl.boolean(startOffset, endOffset, irBuiltIns.booleanType, isInterface)
            }
        }
    }
}

private fun resolveClassSymbol(pluginContext: IrPluginContext, classId: ClassId) =
    pluginContext.findClassCompat(classId)

private fun irClassInternalName(klass: IrClass): String {
    val names = mutableListOf<String>()
    var current: IrElement? = klass
    while (current is IrClass) {
        names.add(current.name.asString())
        current = current.parent
    }
    val packageFqName = (current as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)
        ?.packageFqName
        ?.asString()
        ?: ""
    val relativeName = names.asReversed().joinToString("$")
    return if (packageFqName.isEmpty()) {
        relativeName
    } else {
        packageFqName.replace('.', '/') + "/" + relativeName
    }
}
