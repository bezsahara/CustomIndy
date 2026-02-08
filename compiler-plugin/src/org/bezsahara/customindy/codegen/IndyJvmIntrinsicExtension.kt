package org.bezsahara.customindy.codegen

import org.bezsahara.customindy.ir.IndyBootstrapIrArg
import org.bezsahara.customindy.ir.indyCallData
import org.bezsahara.customindy.ir.JvmIndySymbols
import org.jetbrains.kotlin.backend.jvm.codegen.BlockInfo
import org.jetbrains.kotlin.backend.jvm.codegen.ExpressionCodegen
import org.jetbrains.kotlin.backend.jvm.codegen.IrCallGenerator
import org.jetbrains.kotlin.backend.jvm.codegen.JvmIrIntrinsicExtension
import org.jetbrains.kotlin.backend.jvm.codegen.MaterialValue
import org.jetbrains.kotlin.backend.jvm.codegen.PromisedValue
import org.jetbrains.kotlin.backend.jvm.intrinsics.IntrinsicMethod
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Type

class IndyJvmIntrinsicExtension(
    private val symbols: JvmIndySymbols,
) : JvmIrIntrinsicExtension {
    override fun getIntrinsic(symbol: IrFunctionSymbol): IntrinsicMethod? {
        return if (symbol == symbols.jvmIndy) IndyInvokeDynamic else null
    }

    override fun rewritePluginDefinedOperationMarker(
        v: org.jetbrains.org.objectweb.asm.commons.InstructionAdapter,
        reifiedInsn: org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode,
        instructions: org.jetbrains.org.objectweb.asm.tree.InsnList,
        type: org.jetbrains.kotlin.ir.types.IrType
    ): Boolean = false
}

private object IndyInvokeDynamic : IntrinsicMethod() {
    override fun invoke(expression: IrFunctionAccessExpression, codegen: ExpressionCodegen, data: BlockInfo): PromisedValue {
        val call = expression as? IrCall ?: error("<jvm-indy> must be a call")
        val indyData = call.indyCallData ?: error("Missing indyCallData for invokedynamic")

        val dynamicCall = call.arguments[0] as? IrCall
            ?: error("<jvm-indy> dynamicCall argument must be a call")
        val dynamicCallee = dynamicCall.symbol.owner
        val dynamicCalleeMethod = codegen.methodSignatureMapper.mapAsmMethod(dynamicCallee)
        val dynamicCalleeArgumentTypes = dynamicCalleeMethod.argumentTypes

        val callGenerator = IrCallGenerator.DefaultCallGenerator
        for (i in dynamicCallee.parameters.indices) {
            val param = dynamicCallee.parameters[i]
            val argType = dynamicCalleeArgumentTypes.getOrElse(i) {
                error("No argument type #$i in dynamic callee")
            }
            val arg = dynamicCall.arguments[i]
            if (arg == null) {
                StackValue.createDefaultValue(argType).put(argType, null, codegen.mv, codegen.typeMapper)
            } else {
                callGenerator.genValueAndPut(param, arg, argType, codegen, data)
            }
        }

        val bootstrapArgs = indyData.bootstrapArgs
            .sortedBy { it.index }
            .map { arg ->
                when (arg) {
                    is IndyBootstrapIrArg.FunctionHandleArg -> {
                        val targetOwner = arg.target.owner
                        val targetMethod = codegen.methodSignatureMapper.mapAsmMethod(targetOwner)
                        val handleOwner =
                            if (targetMethod.descriptor == dynamicCalleeMethod.descriptor) targetOwner else dynamicCallee
                        codegen.methodSignatureMapper.mapToMethodHandle(handleOwner)
                    }
                    else -> toAsmBootstrapArg(arg, codegen)
                }
            }
            .toTypedArray()

        codegen.mv.invokedynamic(
            dynamicCalleeMethod.name,
            dynamicCalleeMethod.descriptor,
            indyData.bootstrapHandle,
            bootstrapArgs
        )

        return MaterialValue(codegen, dynamicCalleeMethod.returnType, expression.type)
    }
}

private fun toAsmBootstrapArg(arg: IndyBootstrapIrArg, codegen: ExpressionCodegen): Any {
    return when (arg) {
        is IndyBootstrapIrArg.IntArg -> arg.value
        is IndyBootstrapIrArg.LongArg -> arg.value
        is IndyBootstrapIrArg.DoubleArg -> arg.value
        is IndyBootstrapIrArg.StringArg -> arg.value
        is IndyBootstrapIrArg.ClassArg ->
            codegen.typeMapper.mapType(arg.type, TypeMappingMode.INVOKE_DYNAMIC_BOOTSTRAP_ARGUMENT)
        is IndyBootstrapIrArg.FunctionHandleArg ->
            codegen.methodSignatureMapper.mapToMethodHandle(arg.target.owner)
        is IndyBootstrapIrArg.MethodTypeArg -> Type.getMethodType(arg.desc)
        is IndyBootstrapIrArg.MethodHandleArg -> {
            val ownerType = codegen.typeMapper.mapType(arg.ownerType)
            Handle(arg.tag, ownerType.internalName, arg.name, arg.desc, arg.isInterface)
        }
    }
}

