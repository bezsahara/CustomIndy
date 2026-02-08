package org.bezsahara.customindy.impl

import org.bezsahara.customindy.annotations.BootstrapIndy
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

internal fun <T> getObjIns(lookup: MethodHandles.Lookup, actualClass: Class<T>): T {
    return try {
        lookup
            .findStaticGetter(actualClass, "INSTANCE", actualClass)
            .invoke() as T
    } catch (_: NoSuchFieldException) {
        lookup
            .findConstructor(actualClass, MethodType.methodType(Void.TYPE))
            .invoke() as T
    }
}


