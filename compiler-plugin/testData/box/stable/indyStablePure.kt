// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.IndyStablePure
import org.bezsahara.customindy.annotations.StableThread

private var pureCount = 0

fun computePure(x: Int): Int {
    pureCount += 1
    return pureCount * 100 + x
}

fun pureCaller(x: Int): Int {
    return (@IndyStablePure(stableThread = StableThread.NONE) computePure(x))
}

fun box(): String {
    pureCount = 0
    val a = pureCaller(1)
    val b = pureCaller(2)
    return if (a == b && pureCount == 1) {
        "OK"
    } else {
        "Fail: a=$a b=$b count=$pureCount"
    }
}
