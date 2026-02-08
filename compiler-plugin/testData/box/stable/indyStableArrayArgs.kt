// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.IndyStable

private var arrayCount = 0

fun computeArrayArgs(a: Array<String>, b: IntArray, s: String, t: Byte): String {
    arrayCount += 1
    return "count=$arrayCount a=${a.joinToString()} b=${b.joinToString()} s=$s t=$t"
}

fun callArrayArgs(a: Array<String>, b: IntArray, s: String, t: Byte): String {
    return (@IndyStable computeArrayArgs(a, b, s, t))
}

fun box(): String {
    arrayCount = 0
    val r1 = callArrayArgs(arrayOf("x", "y"), intArrayOf(1, 2), "ok", 3.toByte())
    val r2 = callArrayArgs(arrayOf("x", "y"), intArrayOf(1, 2), "ok", 3.toByte())
    return if (r1 == r2 && arrayCount == 1) {
        "OK"
    } else {
        "Fail: r1=$r1 r2=$r2 count=$arrayCount"
    }
}
