package org.bezsahara.customindy.ir

import org.bezsahara.customindy.annotations.MHKind
import org.bezsahara.customindy.codegen.IndyJvmIntrinsicExtension
import org.bezsahara.customindy.shared.IndyBootstrapArg
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.bezsahara.customindy.shared.IndyClassIds
import org.bezsahara.customindy.shared.IndyFqNames
import org.bezsahara.customindy.shared.IndyLog
import org.bezsahara.customindy.shared.StableCallKind
import org.bezsahara.customindy.shared.StableCallSiteRegistry
import org.bezsahara.customindy.shared.findClassCompat
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.LoweringContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrIntrinsicExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmSyntheticAccessorGenerator
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
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
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
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

private const val AUTO_BOOTSTRAP_METHOD_NAME = "bootstrap"
private const val AUTO_BOOTSTRAP_DESCRIPTOR =
    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"
private const val STABLE_BOOTSTRAP_PURE_METHOD_NAME = "bootstrapPure"
private const val STABLE_BOOTSTRAP_PURE_DESCRIPTOR =
    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;IJI)Ljava/lang/invoke/CallSite;"
private const val STABLE_BOOTSTRAP_UNPURE_METHOD_NAME = "bootstrapUnpure"
private const val STABLE_BOOTSTRAP_UNPURE_DESCRIPTOR =
    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/Class;IJI)Ljava/lang/invoke/CallSite;"

class IndyIrGenerationExtension(
    private val registry: IndyCallSiteRegistry,
    private val stableRegistry: StableCallSiteRegistry,
) : IrGenerationExtension {
    @Volatile
    private var jvmIndySymbols: JvmIndySymbols? = null
    @Volatile
    private var pendingModule: IrModuleFragment? = null
    @Volatile
    private var pendingPluginContext: IrPluginContext? = null
    @Volatile
    private var stableRewritten: Boolean = false

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        pendingModule = moduleFragment
        pendingPluginContext = pluginContext
        val symbols = buildJvmIndySymbols(pluginContext)
        jvmIndySymbols = symbols
        val transformer = IndyCallTransformer(pluginContext, registry, symbols)
        moduleFragment.transform(transformer, null)
    }

    override fun getPlatformIntrinsicExtension(loweringContext: LoweringContext): IrIntrinsicExtension? {
        val jvmContext = loweringContext as? JvmBackendContext ?: return null
        if (jvmContext.irPluginContext == null) return null
        val symbols = jvmIndySymbols ?: return null
        // Needs JVM backend context for synthetic accessor generation.
        maybeRewriteStableCalls(jvmContext, symbols)
        return IndyJvmIntrinsicExtension(symbols)
    }

    private fun maybeRewriteStableCalls(context: JvmBackendContext, symbols: JvmIndySymbols) {
        if (stableRewritten) return
        val module = pendingModule ?: return
        val pluginContext = pendingPluginContext ?: return
        val transformer = IndyStableCallTransformer(pluginContext, stableRegistry, symbols, context)
        module.transform(transformer, null)
        transformer.flushAccessors()
        stableRewritten = true
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

        val filePath = currentFilePath ?: return call
        if (call.startOffset < 0 || call.endOffset < 0) return call

        val indyInfo = callee.extractIndyInfo(pluginContext) ?: return call
        if (callee.dispatchReceiverParameter != null) {
            return call
        }

        val siteInfo = registry.find(filePath, call.startOffset, call.endOffset)
        if (siteInfo == null) {
            IndyLog.warn {
                "No call-site data for indy call at $filePath [${call.startOffset}, ${call.endOffset}]; " +
                        "using empty bootstrap args"
            }
        }
//        val bsmArgs = siteInfo
//            ?.args
//            ?.mapNotNull { it.toIrArg(pluginContext) }
//            ?.sortedBy { it.index }
//            ?: emptyList()

        val siteArgs = siteInfo
            ?.args
            ?.mapNotNull { it.toIrArg(pluginContext, indyInfo.bootstrapArgShift) }
            ?: emptyList()
        val bsmArgs = (indyInfo.extraBootstrapArgs() + siteArgs).sortedBy { it.index }

        IndyLog.warn {
            "Rewriting indy call at $filePath [${call.startOffset}, ${call.endOffset}] " +
                    "bsmArgs=${bsmArgs.size}"
        }

        val bsmHandleCall = buildJvmMethodHandleCall(
            call.startOffset,
            call.endOffset,
            pluginContext,
            symbols,
            indyInfo.handle
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
            bootstrapHandle = indyInfo.handle,
            bootstrapArgs = bsmArgs,
        )

        return indyCall
    }
}

private class IndyStableCallTransformer(
    private val pluginContext: IrPluginContext,
    private val stableRegistry: StableCallSiteRegistry,
    private val symbols: JvmIndySymbols,
    backendContext: JvmBackendContext,
) : IrElementTransformerVoidWithContext() {
    private val accessorGenerator = JvmSyntheticAccessorGenerator(backendContext)
    private val pendingAccessors = mutableSetOf<IrFunction>()
    private var currentFilePath: String? = null

    fun flushAccessors() {
        for (accessor in pendingAccessors) {
            val parent = accessor.parent as? IrDeclarationContainer ?: continue
            if (accessor !in parent.declarations) {
                parent.declarations.add(accessor)
            }
        }
        pendingAccessors.clear()
    }

    override fun visitFileNew(declaration: IrFile): IrFile {
        val prev = currentFilePath
        currentFilePath = declaration.fileEntry.name
        val result = super.visitFileNew(declaration)
        currentFilePath = prev
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val call = super.visitCall(expression) as IrCall
        val callee = call.symbol.owner as? IrSimpleFunction ?: return call

        val filePath = currentFilePath ?: return call
        if (call.startOffset < 0 || call.endOffset < 0) return call

        val stableInfo = stableRegistry.find(filePath, call.startOffset, call.endOffset) ?: return call
        if (callee.dispatchReceiverParameter != null) {
            IndyLog.warn {
                "Stable call at $filePath [${call.startOffset}, ${call.endOffset}] is not static; skipping"
            }
            return call
        }

        val targetSymbol = resolveAccessibleTarget(call, callee)
        return rewriteStableCall(call, callee, filePath, stableInfo, targetSymbol)
    }

    private fun resolveAccessibleTarget(call: IrCall, callee: IrSimpleFunction): IrFunctionSymbol {
        if (!DescriptorVisibilities.isPrivate(callee.visibility)) {
            return callee.symbol
        }
        val parent = callee.parent
        val currentOwner = currentClass?.irElement as? IrClass
        val needsAccessor = when (parent) {
            is IrClass -> currentOwner != parent
            is IrFile -> currentOwner != null
            else -> false
        }
        if (!needsAccessor) return callee.symbol

        val accessor = accessorGenerator.getSyntheticFunctionAccessor(call, allScopes).also {
            pendingAccessors.add(it)
        }
        return accessor.symbol
    }

    private fun rewriteStableCall(
        call: IrCall,
        callee: IrSimpleFunction,
        filePath: String,
        stableInfo: org.bezsahara.customindy.shared.StableCallSiteInfo,
        targetSymbol: IrFunctionSymbol,
    ): IrExpression {
        val stableBootstrapSymbol = pluginContext.findClassCompat(IndyClassIds.STABLE_BOOTSTRAP) ?: return call
        val stableBootstrapInternalName = irClassInternalName(stableBootstrapSymbol.owner)

        val handle = when (stableInfo.kind) {
            StableCallKind.PURE -> Handle(
                Opcodes.H_INVOKESTATIC,
                stableBootstrapInternalName,
                STABLE_BOOTSTRAP_PURE_METHOD_NAME,
                STABLE_BOOTSTRAP_PURE_DESCRIPTOR,
                false
            )
            StableCallKind.UNPURE -> Handle(
                Opcodes.H_INVOKESTATIC,
                stableBootstrapInternalName,
                STABLE_BOOTSTRAP_UNPURE_METHOD_NAME,
                STABLE_BOOTSTRAP_UNPURE_DESCRIPTOR,
                false
            )
        }

        val bsmArgs = mutableListOf<IndyBootstrapIrArg>()
        bsmArgs += IndyBootstrapIrArg.FunctionHandleArg(index = 0, target = targetSymbol)

        if (stableInfo.kind == StableCallKind.UNPURE) {
            val checkerId = stableInfo.checkerClassId ?: return call
            val checkerSymbol = resolveClassSymbol(pluginContext, checkerId) ?: return call
            bsmArgs += IndyBootstrapIrArg.ClassArg(index = 1, type = checkerSymbol.defaultType)
            bsmArgs += IndyBootstrapIrArg.IntArg(index = 2, value = stableInfo.stableThread.ordinal)
        } else {
            bsmArgs += IndyBootstrapIrArg.IntArg(index = 1, value = stableInfo.stableThread.ordinal)
        }
        val defaultInfo = extractDefaultInfo(call, callee)
        val maskIndex = if (stableInfo.kind == StableCallKind.UNPURE) 3 else 2
        bsmArgs += IndyBootstrapIrArg.LongArg(index = maskIndex, value = defaultInfo.mask)
        bsmArgs += IndyBootstrapIrArg.IntArg(index = maskIndex + 1, value = defaultInfo.paramCount)

        IndyLog.warn {
            "Rewriting stable call at $filePath [${call.startOffset}, ${call.endOffset}] " +
                "kind=${stableInfo.kind} thread=${stableInfo.stableThread}"
        }

        val bsmHandleCall = buildJvmMethodHandleCall(
            call.startOffset,
            call.endOffset,
            pluginContext,
            symbols,
            handle
        )
        val bsmArgExpressions = bsmArgs
            .sortedBy { it.index }
            .mapNotNull { it.toIrExpression(pluginContext, symbols, call.startOffset, call.endOffset) }
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
            targetSymbol = callee.symbol,
            dynamicCall = call,
            bootstrapHandle = handle,
            bootstrapArgs = bsmArgs,
        )

        return indyCall
    }

    private data class DefaultInfo(
        val mask: Long,
        val paramCount: Int,
    )

    @OptIn(DeprecatedForRemovalCompilerApi::class)
    private fun extractDefaultInfo(call: IrCall, callee: IrSimpleFunction): DefaultInfo {
        val params = callee.parameters
        val receiverOffset = params.size - callee.valueParameters.size
        val maskParams = params.filter { param ->
            val name = param.name.asString()
            name.startsWith("\$mask") && param.type == pluginContext.irBuiltIns.intType
        }
        if (maskParams.isEmpty()) {
            return DefaultInfo(mask = 0L, paramCount = params.size)
        }

        val firstMaskIndex = params.indexOf(maskParams.first()).let { if (it < 0) params.size else it }
        val maskValues = maskParams.map { param ->
            val arg = call.arguments[param.indexInParameters] as? IrConst
            (arg?.value as? Int) ?: 0
        }
        var mask = 0L
        if (maskValues.isNotEmpty()) {
            mask = maskValues[0].toLong() and 0xffffffffL
        }
        if (maskValues.size > 1) {
            mask = mask or ((maskValues[1].toLong() and 0xffffffffL) shl 32)
        }
        val alignedMask = if (receiverOffset <= 0) {
            mask
        } else if (receiverOffset >= 64) {
            0L
        } else {
            mask shl receiverOffset
        }
        return DefaultInfo(mask = alignedMask, paramCount = firstMaskIndex)
    }
}

private sealed interface IndyInfo {
    val handle: Handle
    val bootstrapArgShift: Int
    fun extraBootstrapArgs(): List<IndyBootstrapIrArg>
}

private data class CustomIndyInfo(
    override val handle: Handle,
) : IndyInfo {
    override val bootstrapArgShift: Int = 0
    override fun extraBootstrapArgs(): List<IndyBootstrapIrArg> = emptyList()
}

private data class SimpleIndyInfo(
    override val handle: Handle,
    private val implClass: IrClassSymbol,
) : IndyInfo {
    override val bootstrapArgShift: Int = 1
    override fun extraBootstrapArgs(): List<IndyBootstrapIrArg> =
        listOf(IndyBootstrapIrArg.ClassArg(index = 0, type = implClass.defaultType))
}

private fun IrSimpleFunction.extractIndyInfo(pluginContext: IrPluginContext): IndyInfo? {
    val customIndy = getAnnotation(IndyFqNames.CUSTOM_INDY)
    if (customIndy != null) {
        fun argByName(name: String): IrExpression? {
            val params = customIndy.symbol.owner.parameters
            val param = params.firstOrNull { it.name.asString() == name } ?: return null
            return customIndy.arguments[param.indexInParameters]
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

    val simpleIndy = getAnnotation(IndyFqNames.SIMPLE_INDY) ?: return null
    fun simpleArgByName(name: String): IrExpression? {
        val params = simpleIndy.symbol.owner.parameters
        val param = params.firstOrNull { it.name.asString() == name } ?: return null
        return simpleIndy.arguments[param.indexInParameters]
    }

    val implClassExpr = simpleArgByName("bsmClass") as? IrClassReference ?: return null
    val implClassSymbol = implClassExpr.symbol as? IrClassSymbol ?: return null

    val autoBootstrapSymbol = pluginContext.findClassCompat(IndyClassIds.AUTO_BOOTSTRAP) ?: return null
    val autoBootstrapInternalName = irClassInternalName(autoBootstrapSymbol.owner)

    val handle = Handle(
        Opcodes.H_INVOKESTATIC,
        autoBootstrapInternalName,
        AUTO_BOOTSTRAP_METHOD_NAME,
        AUTO_BOOTSTRAP_DESCRIPTOR,
        false
    )

    return SimpleIndyInfo(
        handle = handle,
        implClass = implClassSymbol,
    )
}

private fun IndyBootstrapArg.toIrArg(pluginContext: IrPluginContext, indexOffset: Int): IndyBootstrapIrArg? {
    return when (this) {
        is IndyBootstrapArg.IntArg -> IndyBootstrapIrArg.IntArg(index + indexOffset, value)
        is IndyBootstrapArg.LongArg -> IndyBootstrapIrArg.LongArg(index + indexOffset, value)
        is IndyBootstrapArg.DoubleArg -> IndyBootstrapIrArg.DoubleArg(index + indexOffset, value)
        is IndyBootstrapArg.StringArg -> IndyBootstrapIrArg.StringArg(index + indexOffset, value)
        is IndyBootstrapArg.ClassArg -> {
            val symbol = resolveClassSymbol(pluginContext, classId) ?: return null
            IndyBootstrapIrArg.ClassArg(index + indexOffset, symbol.defaultType)
        }
        is IndyBootstrapArg.MethodHandleArg -> {
            val symbol = resolveClassSymbol(pluginContext, ownerClassId) ?: return null
            val isInterface = symbol.owner.kind == ClassKind.INTERFACE
            IndyBootstrapIrArg.MethodHandleArg(
                index + indexOffset,
                symbol.defaultType,
                name,
                desc,
                Opcodes.H_INVOKESTATIC,
                isInterface
            )
        }
        is IndyBootstrapArg.MethodTypeArg -> IndyBootstrapIrArg.MethodTypeArg(index + indexOffset, desc)
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
        is IndyBootstrapIrArg.FunctionHandleArg ->
            IrCallImpl.fromSymbolOwner(startOffset, endOffset, irBuiltIns.anyType, symbols.jvmMethodHandle).apply {
                arguments[0] = IrConstImpl.int(startOffset, endOffset, irBuiltIns.intType, 0)
                arguments[1] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, "")
                arguments[2] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, "")
                arguments[3] = IrConstImpl.string(startOffset, endOffset, irBuiltIns.stringType, "")
                arguments[4] = IrConstImpl.boolean(startOffset, endOffset, irBuiltIns.booleanType, false)
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
