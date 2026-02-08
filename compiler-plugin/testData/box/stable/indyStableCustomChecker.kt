// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.IndyStable
import org.bezsahara.customindy.annotations.IndyStableChecker
import org.bezsahara.customindy.annotations.StableThread
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

private var customCount = 0

object NeverChangeChecker : IndyStableChecker {
    @JvmStatic
    fun check(oldValue: Int, newValue: Int): Boolean = false

    override fun checkerMethodHandle(): MethodHandle {
        return MethodHandles.lookup().findStatic(
            NeverChangeChecker::class.java,
            "check",
            MethodType.methodType(java.lang.Boolean.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
        )
    }
}

fun computeCustom(x: Int): Int {
    customCount += 1
    return customCount * 1000 + x
}

fun customCaller(x: Int): Int {
    return (@IndyStable(checker = NeverChangeChecker::class, stableThread = StableThread.NONE) computeCustom(x))
}

fun box(): String {
    customCount = 0
    val a = customCaller(1)
    val b = customCaller(2)
    return if (a == b && customCount == 1) {
        "OK"
    } else {
        "Fail: a=$a b=$b count=$customCount"
    }
}
