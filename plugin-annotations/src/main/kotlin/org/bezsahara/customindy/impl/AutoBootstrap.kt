package org.bezsahara.customindy.impl

import org.bezsahara.customindy.annotations.BootstrapIndy

/**
 * Bootstrap implementation used by `@SimpleIndy`.
 *
 * The first bootstrap argument is the implementing class, and the remaining
 * arguments come from `@Indy*` annotations on the call site.
 */
public object AutoBootstrap {
    @JvmStatic
    public fun bootstrap(
        lookup: java.lang.invoke.MethodHandles.Lookup,
        name: String,
        type: java.lang.invoke.MethodType,
        actualClass: Class<out BootstrapIndy>,
        vararg args: Any
    ): java.lang.invoke.CallSite {
        // if it is a kotlin object just get its INSTANCE
        // otherwise create it
        val instance = getObjIns(lookup, actualClass)

        return instance.bootstrap(lookup, name, type, args)
    }
}
