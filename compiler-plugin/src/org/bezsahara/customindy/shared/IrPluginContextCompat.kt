package org.bezsahara.customindy.shared

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal fun IrPluginContext.findFunctionsCompat(callableId: CallableId): Collection<IrSimpleFunctionSymbol> {
    val finder = invokeFinderForBuiltins(this)
    if (finder != null) {
        return invokeFinderFunctions(finder, callableId)
    }
    @Suppress("DEPRECATION")
    return referenceFunctions(callableId)
}

internal fun IrPluginContext.findClassCompat(classId: ClassId): IrClassSymbol? {
    val finder = invokeFinderForBuiltins(this)
    if (finder != null) {
        return invokeFinderClass(finder, classId)
    }
    @Suppress("DEPRECATION")
    return referenceClass(classId)
}

private fun invokeFinderForBuiltins(ctx: IrPluginContext): Any? {
    val method = ctx.javaClass.methods.firstOrNull { it.name == "finderForBuiltins" && it.parameterCount == 0 }
        ?: return null
    return method.invoke(ctx)
}

private fun invokeFinderFunctions(finder: Any, callableId: CallableId): Collection<IrSimpleFunctionSymbol> {
    val method = finder.javaClass.methods.firstOrNull { it.name == "findFunctions" && it.parameterCount == 1 }
        ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return method.invoke(finder, callableId) as? Collection<IrSimpleFunctionSymbol> ?: emptyList()
}

private fun invokeFinderClass(finder: Any, classId: ClassId): IrClassSymbol? {
    val method = finder.javaClass.methods.firstOrNull { it.name == "findClass" && it.parameterCount == 1 }
        ?: return null
    return method.invoke(finder, classId) as? IrClassSymbol
}
