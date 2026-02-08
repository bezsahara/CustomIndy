package org.bezsahara.customindy.fir

import org.bezsahara.customindy.shared.CallSiteKey
import org.bezsahara.customindy.shared.IndyBootstrapArg
import org.bezsahara.customindy.shared.IndyCallSiteInfo
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.bezsahara.customindy.shared.IndyClassIds
import org.bezsahara.customindy.shared.IndyLog
import org.bezsahara.customindy.shared.StableCallKind
import org.bezsahara.customindy.shared.StableCallSiteInfo
import org.bezsahara.customindy.shared.StableCallSiteRegistry
import org.bezsahara.customindy.shared.StableThreadMode
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.session.sourcesToPathsMapper
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

class IndyFirAdditionalCheckers(
    session: FirSession,
    private val registry: IndyCallSiteRegistry,
    private val stableRegistry: StableCallSiteRegistry,
) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker>
            get() = setOf(IndyFirFunctionCallChecker(registry, stableRegistry))
    }
}

private class IndyFirFunctionCallChecker(
    private val registry: IndyCallSiteRegistry,
    private val stableRegistry: StableCallSiteRegistry,
) : FirFunctionCallChecker(MppCheckerKind.Common) {
    @OptIn(SymbolInternals::class)
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val stableInfo = extractStableInfo(expression)
        if (stableInfo != null) {
            val source = expression.source ?: return
            val filePath = context.containingFilePath
                ?: context.session.sourcesToPathsMapper.getSourceFilePath(source)
                ?: return
            val endOffset = source.endOffset
            val key = CallSiteKey(filePath, source.startOffset, endOffset)
            stableRegistry.record(stableInfo.copy(key = key))
            IndyLog.warn {
                "Recorded stable call site: $filePath [${source.startOffset}, $endOffset] " +
                        "kind=${stableInfo.kind} thread=${stableInfo.stableThread}"
            }
            return
        }

        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val isIndy = callee.fir.annotations.hasAnnotation(IndyClassIds.CUSTOM_INDY, context.session) ||
                callee.fir.annotations.hasAnnotation(IndyClassIds.SIMPLE_INDY, context.session)
        if (!isIndy) {
            return
        }

        val args = extractBootstrapArgs(expression)
        if (args.isEmpty()) return

        val source = expression.source ?: return
        val filePath = context.containingFilePath
            ?: context.session.sourcesToPathsMapper.getSourceFilePath(source)
            ?: return

        val normalized = validateAndSort(args)
        val endOffset = source.endOffset
        val fullKey = CallSiteKey(filePath, source.startOffset, endOffset)
        registry.record(IndyCallSiteInfo(fullKey, normalized))
        IndyLog.warn {
            "Recorded CustomIndy call site: $filePath [${source.startOffset}, $endOffset] " +
                    "args=${normalized.size}"
        }

        val calleeSource = expression.calleeReference.source
        if (calleeSource != null) {
            val calleeStart = calleeSource.startOffset
            val calleeEnd = calleeSource.endOffset
            if (calleeStart != source.startOffset || calleeEnd != endOffset) {
                val calleeKey = CallSiteKey(filePath, calleeStart, endOffset)
                registry.record(IndyCallSiteInfo(calleeKey, normalized))
                IndyLog.warn {
                    "Recorded CustomIndy callee-range key: $filePath [$calleeStart, $endOffset]"
                }
            }
            if (calleeEnd != endOffset) {
                val calleeRangeKey = CallSiteKey(filePath, calleeStart, calleeEnd)
                registry.record(IndyCallSiteInfo(calleeRangeKey, normalized))
                IndyLog.warn {
                    "Recorded CustomIndy callee-only key: $filePath [$calleeStart, $calleeEnd]"
                }
            }
        }
    }
}

private fun extractStableInfo(expression: FirFunctionCall): StableCallSiteInfo? {
    val stable = expression.annotations.firstOrNull {
        it.annotationTypeRef.coneType.classId == IndyClassIds.INDY_STABLE
    }
    val stablePure = expression.annotations.firstOrNull {
        it.annotationTypeRef.coneType.classId == IndyClassIds.INDY_STABLE_PURE
    }
    if (stable == null && stablePure == null) return null
    if (stable != null && stablePure != null) {
        error("IndyStable and IndyStablePure cannot be used on the same call site.")
    }

    val stableThreadName = (stable ?: stablePure)?.getEnumArgName("stableThread")
    val stableThread = stableThreadName?.let { StableThreadMode.valueOf(it) }
        ?: StableThreadMode.SYNCHRONIZED

    return if (stablePure != null) {
        StableCallSiteInfo(
            key = CallSiteKey("", 0, 0),
            kind = StableCallKind.PURE,
            stableThread = stableThread,
            checkerClassId = null,
        )
    } else {
        val checkerClass = stable?.getClassIdArg("checker") ?: IndyClassIds.SELF_GENERATE_CHECKER
        StableCallSiteInfo(
            key = CallSiteKey("", 0, 0),
            kind = StableCallKind.UNPURE,
            stableThread = stableThread,
            checkerClassId = checkerClass,
        )
    }
}

private fun extractBootstrapArgs(expression: FirFunctionCall): List<IndyBootstrapArg> {
    val result = mutableListOf<IndyBootstrapArg>()
    for (annotation in expression.annotations) {
        val classId = annotation.annotationTypeRef.coneType.classId ?: continue
        when (classId) {
            IndyClassIds.INDY_INT -> {
                val index = annotation.getIntArg("index") ?: continue
                val value = annotation.getIntArg("value") ?: continue
                result.add(IndyBootstrapArg.IntArg(index, value))
            }
            IndyClassIds.INDY_LONG -> {
                val index = annotation.getIntArg("index") ?: continue
                val value = annotation.getLongArg("value") ?: continue
                result.add(IndyBootstrapArg.LongArg(index, value))
            }
            IndyClassIds.INDY_DOUBLE -> {
                val index = annotation.getIntArg("index") ?: continue
                val value = annotation.getDoubleArg("value") ?: continue
                result.add(IndyBootstrapArg.DoubleArg(index, value))
            }
            IndyClassIds.INDY_STR -> {
                val index = annotation.getIntArg("index") ?: continue
                val value = annotation.getStringArg("value") ?: continue
                result.add(IndyBootstrapArg.StringArg(index, value))
            }
            IndyClassIds.INDY_CLASS -> {
                val index = annotation.getIntArg("index") ?: continue
                val classId = annotation.getClassIdArg("value") ?: continue
                result.add(IndyBootstrapArg.ClassArg(index, classId))
            }
            IndyClassIds.INDY_METHOD_HANDLE -> {
                val index = annotation.getIntArg("index") ?: continue
                val owner = annotation.getClassIdArg("owner") ?: continue
                val name = annotation.getStringArg("name") ?: continue
                val desc = annotation.getStringArg("desc") ?: continue
                result.add(IndyBootstrapArg.MethodHandleArg(index, owner, name, desc))
            }
            IndyClassIds.INDY_METHOD_TYPE -> {
                val index = annotation.getIntArg("index") ?: continue
                val desc = annotation.getStringArg("desc") ?: continue
                result.add(IndyBootstrapArg.MethodTypeArg(index, desc))
            }
        }
    }
    return result
}

private fun FirAnnotation.getIntArg(name: String): Int? =
    getLiteralArg(name)?.toIntValue()

private fun FirAnnotation.getLongArg(name: String): Long? =
    getLiteralArg(name)?.toLongValue()

private fun FirAnnotation.getDoubleArg(name: String): Double? =
    getLiteralArg(name)?.toDoubleValue()

private fun FirAnnotation.getStringArg(name: String): String? {
    val literal = getLiteralArg(name) ?: return null
    return if (literal.kind == ConstantValueKind.String) literal.value as? String else null
}

private fun FirAnnotation.getClassIdArg(name: String): ClassId? {
    val expr = argumentMapping.mapping[Name.identifier(name)] ?: return null
    return when (expr) {
        is FirGetClassCall -> expr.argument.resolvedType.classId
        is FirClassReferenceExpression -> expr.classTypeRef.coneType.classId
        else -> null
    }
}

private fun FirAnnotation.getEnumArgName(name: String): String? {
    val expr = argumentMapping.mapping[Name.identifier(name)] as? FirQualifiedAccessExpression ?: return null
    val symbol = expr.calleeReference.toResolvedCallableSymbol() as? FirEnumEntrySymbol ?: return null
    return symbol.name.asString()
}

private fun FirAnnotation.getLiteralArg(name: String): FirLiteralExpression? {
    val expr = argumentMapping.mapping[Name.identifier(name)] ?: return null
    return expr as? FirLiteralExpression
}

private fun FirLiteralExpression.toIntValue(): Int? {
    return when (kind) {
        ConstantValueKind.Int,
        ConstantValueKind.Short,
        ConstantValueKind.Byte,
        ConstantValueKind.Long,
        ConstantValueKind.IntegerLiteral,
        ConstantValueKind.UnsignedInt,
        ConstantValueKind.UnsignedShort,
        ConstantValueKind.UnsignedByte,
        ConstantValueKind.UnsignedLong,
        ConstantValueKind.UnsignedIntegerLiteral ->
            value.toIntOrNull()
        ConstantValueKind.Char -> (value as? Char)?.code
        else -> null
    }
}

private fun FirLiteralExpression.toLongValue(): Long? {
    return when (kind) {
        ConstantValueKind.Int,
        ConstantValueKind.Short,
        ConstantValueKind.Byte,
        ConstantValueKind.Long,
        ConstantValueKind.IntegerLiteral,
        ConstantValueKind.UnsignedInt,
        ConstantValueKind.UnsignedShort,
        ConstantValueKind.UnsignedByte,
        ConstantValueKind.UnsignedLong,
        ConstantValueKind.UnsignedIntegerLiteral ->
            value.toLongOrNull()
        ConstantValueKind.Char -> (value as? Char)?.code?.toLong()
        else -> null
    }
}

private fun FirLiteralExpression.toDoubleValue(): Double? {
    return when (kind) {
        ConstantValueKind.Double,
        ConstantValueKind.Float,
        ConstantValueKind.Int,
        ConstantValueKind.Short,
        ConstantValueKind.Byte,
        ConstantValueKind.Long,
        ConstantValueKind.IntegerLiteral,
        ConstantValueKind.UnsignedInt,
        ConstantValueKind.UnsignedShort,
        ConstantValueKind.UnsignedByte,
        ConstantValueKind.UnsignedLong,
        ConstantValueKind.UnsignedIntegerLiteral ->
            value.toDoubleOrNull()
        ConstantValueKind.Char -> (value as? Char)?.code?.toDouble()
        else -> null
    }
}

private fun Any?.toIntOrNull(): Int? = when (this) {
    is Int -> this
    is Long -> this.toInt()
    is Short -> this.toInt()
    is Byte -> this.toInt()
    is UInt -> this.toInt()
    is ULong -> this.toInt()
    is UShort -> this.toInt()
    is UByte -> this.toInt()
    is Number -> this.toInt()
    else -> null
}

private fun Any?.toLongOrNull(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Short -> this.toLong()
    is Byte -> this.toLong()
    is UInt -> this.toLong()
    is ULong -> this.toLong()
    is UShort -> this.toLong()
    is UByte -> this.toLong()
    is Number -> this.toLong()
    else -> null
}

private fun Any?.toDoubleOrNull(): Double? = when (this) {
    is Double -> this
    is Float -> this.toDouble()
    is Long -> this.toDouble()
    is Int -> this.toDouble()
    is Short -> this.toDouble()
    is Byte -> this.toDouble()
    is UInt -> this.toDouble()
    is ULong -> this.toDouble()
    is UShort -> this.toDouble()
    is UByte -> this.toDouble()
    is Number -> this.toDouble()
    else -> null
}

private fun validateAndSort(args: List<IndyBootstrapArg>): List<IndyBootstrapArg> {
    if (args.isEmpty()) return args

    val sorted = args.sortedBy { it.index }
    val indices = sorted.map { it.index }
    if (indices.any { it < 0 }) {
        error("Indy bootstrap argument index must be >= 0: $indices")
    }
    if (indices.distinct().size != indices.size) {
        error("Duplicate indy bootstrap argument index: $indices")
    }
    if (indices.first() != 0 || indices.last() != indices.size - 1) {
        error("Indy bootstrap argument indexes must be contiguous from 0: $indices")
    }

    return sorted
}
