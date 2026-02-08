// WITH_STDLIB

package foo.bar

import org.bezsahara.customindy.annotations.CustomIndy
import org.bezsahara.customindy.annotations.IndyInt
import org.bezsahara.customindy.annotations.MHKind
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object InlineBootstrap {
    @JvmStatic
    fun bootstrap(
        lookup: MethodHandles.Lookup,
        name: String,
        type: MethodType,
        id: Int,
    ): CallSite {
        val mh = MethodHandles.constant(String::class.java, "inline=$id")
        return ConstantCallSite(mh.asType(type))
    }
}

@CustomIndy(
    bsmKind = MHKind.INVOKESTATIC,
    bsmOwner = InlineBootstrap::class,
    bsmName = "bootstrap",
    bsmDesc = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;",
)
fun greetInline(): String = "fallback"

inline fun inlineCaller(): String {
    return (@IndyInt(index = 0, value = 5) greetInline())
}

fun box(): String {
    val result = inlineCaller()
    return if (result == "inline=5") "OK" else "Fail: $result"
}
