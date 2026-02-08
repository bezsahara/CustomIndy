// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.IndyStable

private var defaultCount = 0

fun computeDefault(a: Int, b: String = "def"): String {
    defaultCount += 1
    return "count=$defaultCount a=$a b=$b"
}

fun stableDefaultCaller(a: Int): String {
    return (@IndyStable computeDefault(a))
}

fun box(): String {
    defaultCount = 0
    val r1 = stableDefaultCaller(1)
    val r2 = stableDefaultCaller(1)
    return if (r1 == r2 && defaultCount == 1) {
        "OK"
    } else {
        "Fail: r1=$r1 r2=$r2 count=$defaultCount"
    }
}
