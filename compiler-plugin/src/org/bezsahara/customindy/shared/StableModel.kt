package org.bezsahara.customindy.shared

import org.jetbrains.kotlin.name.ClassId
import java.util.concurrent.ConcurrentHashMap

enum class StableCallKind {
    PURE,
    UNPURE,
}

enum class StableThreadMode {
    SYNCHRONIZED,
    NONE,
}

data class StableCallSiteInfo(
    val key: CallSiteKey,
    val kind: StableCallKind,
    val stableThread: StableThreadMode,
    val checkerClassId: ClassId?,
)

class StableCallSiteRegistry {
    private val callSites = ConcurrentHashMap<CallSiteKey, StableCallSiteInfo>()

    fun record(info: StableCallSiteInfo) {
        val normalizedKey = info.key.normalized()
        callSites[normalizedKey] =
            if (normalizedKey == info.key) info else info.copy(key = normalizedKey)
    }

    fun find(filePath: String, startOffset: Int, endOffset: Int): StableCallSiteInfo? {
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
