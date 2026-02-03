package org.bezsahara.customindy.ir

import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType

data class JvmIndySymbols(
    val jvmIndy: IrSimpleFunctionSymbol,
    val jvmMethodHandle: IrSimpleFunctionSymbol,
    val jvmMethodType: IrSimpleFunctionSymbol,
    val arrayOfAnyType: IrType,
)
