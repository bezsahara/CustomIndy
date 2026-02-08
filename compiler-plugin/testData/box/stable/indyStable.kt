// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.IndyStable

private var stableCount = 0

fun computeStable(x: Int): Int {
    stableCount += 1
    return stableCount * 10 + x
}

fun stableCaller(x: Int): Int {
    return (@IndyStable computeStable(x))
}

fun box(): String {
    stableCount = 0
    val a = stableCaller(3)
    val b = stableCaller(3)
    val c = stableCaller(4)
    return if (a == b && c != a && stableCount == 2) {
        "OK"
    } else {
        "Fail: a=$a b=$b c=$c count=$stableCount"
    }
}
