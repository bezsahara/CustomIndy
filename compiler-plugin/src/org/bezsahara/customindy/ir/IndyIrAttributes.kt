package org.bezsahara.customindy.ir

import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.org.objectweb.asm.Handle

sealed interface IndyBootstrapIrArg {
    val index: Int

    data class IntArg(override val index: Int, val value: Int) : IndyBootstrapIrArg
    data class LongArg(override val index: Int, val value: Long) : IndyBootstrapIrArg
    data class DoubleArg(override val index: Int, val value: Double) : IndyBootstrapIrArg
    data class StringArg(override val index: Int, val value: String) : IndyBootstrapIrArg
    data class ClassArg(override val index: Int, val type: IrType) : IndyBootstrapIrArg
    data class MethodHandleArg(
        override val index: Int,
        val ownerType: IrType,
        val name: String,
        val desc: String,
        val tag: Int,
        val isInterface: Boolean,
    ) : IndyBootstrapIrArg
    data class MethodTypeArg(override val index: Int, val desc: String) : IndyBootstrapIrArg
}

data class IndyCallData(
    val targetSymbol: IrFunctionSymbol,
    val dynamicCall: IrCall,
    val bootstrapHandle: Handle,
    val bootstrapArgs: List<IndyBootstrapIrArg>,
)

var IrCall.indyCallData: IndyCallData? by irAttribute(copyByDefault = false)
