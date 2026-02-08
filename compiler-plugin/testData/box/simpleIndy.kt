// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.BootstrapIndy
import org.bezsahara.customindy.annotations.IndyInt
import org.bezsahara.customindy.annotations.IndyStr
import org.bezsahara.customindy.annotations.SimpleIndy
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

class SimpleBootstrap : BootstrapIndy {
    override fun bootstrap(
        lookup: MethodHandles.Lookup,
        name: String,
        type: MethodType,
        args: Array<out Any>,
    ): CallSite {
        val msg = "args=${args[0]},${args[1]}"
        val mh = MethodHandles.constant(String::class.java, msg)
        return ConstantCallSite(mh.asType(type))
    }
}

@SimpleIndy(bsmClass = SimpleBootstrap::class)
fun greetSimple(): String = "fallback"

fun box(): String {
    val result =
        @IndyInt(index = 0, value = 3)
        @IndyStr(index = 1, value = "ok")
        greetSimple()
    return if (result == "args=3,ok") "OK" else "Fail: $result"
}
