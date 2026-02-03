package org.bezsahara.customindy.shared

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.util.concurrent.ConcurrentHashMap

object IndyFqNames {
    val INDY_INT = FqName("org.bezsahara.customindy.annotations.IndyInt")
    val INDY_LONG = FqName("org.bezsahara.customindy.annotations.IndyLong")
    val INDY_DOUBLE = FqName("org.bezsahara.customindy.annotations.IndyDouble")
    val INDY_STR = FqName("org.bezsahara.customindy.annotations.IndyStr")
    val INDY_CLASS = FqName("org.bezsahara.customindy.annotations.IndyClass")
    val INDY_METHOD_HANDLE = FqName("org.bezsahara.customindy.annotations.IndyMethodHandle")
    val INDY_METHOD_TYPE = FqName("org.bezsahara.customindy.annotations.IndyMethodType")
    val CUSTOM_INDY = FqName("org.bezsahara.customindy.annotations.CustomIndy")
    val INDY_CALL = FqName("org.bezsahara.customindy.runtime.indyCall")
}

object IndyClassIds {
    val INDY_INT = ClassId.topLevel(IndyFqNames.INDY_INT)
    val INDY_LONG = ClassId.topLevel(IndyFqNames.INDY_LONG)
    val INDY_DOUBLE = ClassId.topLevel(IndyFqNames.INDY_DOUBLE)
    val INDY_STR = ClassId.topLevel(IndyFqNames.INDY_STR)
    val INDY_CLASS = ClassId.topLevel(IndyFqNames.INDY_CLASS)
    val INDY_METHOD_HANDLE = ClassId.topLevel(IndyFqNames.INDY_METHOD_HANDLE)
    val INDY_METHOD_TYPE = ClassId.topLevel(IndyFqNames.INDY_METHOD_TYPE)
    val CUSTOM_INDY = ClassId.topLevel(IndyFqNames.CUSTOM_INDY)
}

data class CallSiteKey(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    fun normalized(): CallSiteKey {
        val normalizedPath = filePath.replace('\\', '/')
        return if (normalizedPath == filePath) this else copy(filePath = normalizedPath)
    }
}

sealed interface IndyBootstrapArg {
    val index: Int

    data class IntArg(override val index: Int, val value: Int) : IndyBootstrapArg
    data class LongArg(override val index: Int, val value: Long) : IndyBootstrapArg
    data class DoubleArg(override val index: Int, val value: Double) : IndyBootstrapArg
    data class StringArg(override val index: Int, val value: String) : IndyBootstrapArg
    data class ClassArg(override val index: Int, val classId: ClassId) : IndyBootstrapArg
    data class MethodHandleArg(
        override val index: Int,
        val ownerClassId: ClassId,
        val name: String,
        val desc: String,
    ) : IndyBootstrapArg
    data class MethodTypeArg(override val index: Int, val desc: String) : IndyBootstrapArg
}

data class IndyCallSiteInfo(
    val key: CallSiteKey,
    val args: List<IndyBootstrapArg>,
)

class IndyCallSiteRegistry {
    private val callSites = ConcurrentHashMap<CallSiteKey, IndyCallSiteInfo>()

    fun record(info: IndyCallSiteInfo) {
        val normalizedKey = info.key.normalized()
        callSites[normalizedKey] =
            if (normalizedKey == info.key) info else info.copy(key = normalizedKey)
    }

    fun find(filePath: String, startOffset: Int, endOffset: Int): IndyCallSiteInfo? {
        val key = CallSiteKey(filePath, startOffset, endOffset).normalized()
        val direct = callSites[key]
        if (direct != null) return direct

        val fileName = key.filePath.substringAfterLast('/')
        if (fileName == key.filePath) return null

        for ((existingKey, info) in callSites) {
            if (existingKey.startOffset != startOffset || existingKey.endOffset != endOffset) continue
            val existingName = existingKey.filePath.substringAfterLast('/')
            if (existingName == fileName) return info
        }
        return null
    }
}
