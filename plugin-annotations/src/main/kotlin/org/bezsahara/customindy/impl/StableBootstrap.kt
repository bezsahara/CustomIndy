package org.bezsahara.customindy.impl

import org.bezsahara.customindy.annotations.IndyStableChecker
import org.bezsahara.customindy.annotations.SelfGenerateChecker
import org.bezsahara.customindy.annotations.StableThread
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicLong

public object StableBootstrap {
    private val counter = AtomicLong()

    @JvmStatic
    public fun bootstrapPure(
        lookup: MethodHandles.Lookup,
        name: String,
        type: MethodType,
        actualFunMH: MethodHandle,
        stableThread: Int,
        defaultMask: Long,
        defaultParamCount: Int,
    ): CallSite {
        val thread = stableThreadFromOrdinal(stableThread)
        val generated = StableAsmGenerator(
            internalName = nextInternalName(lookup.lookupClass()),
            callType = type,
            stableThread = thread,
            isPure = true,
            useCheckerHandle = false,
            defaultMask = defaultMask,
            defaultParamCount = defaultParamCount,
            loader = lookup.lookupClass().classLoader,
        ).generate()

        val classLookup = lookup.defineHiddenClass(
            generated.bytes,
            true,
            MethodHandles.Lookup.ClassOption.NESTMATE,
            MethodHandles.Lookup.ClassOption.STRONG,
        )
        val klass = classLookup.lookupClass()
        val invoke = classLookup.findStatic(klass, "invoke", generated.invokeType)
        val target = MethodHandles.insertArguments(invoke, 0, actualFunMH).asType(type)
        return ConstantCallSite(target)
    }

    @JvmStatic
    public fun bootstrapUnpure(
        lookup: MethodHandles.Lookup,
        name: String,
        type: MethodType,
        actualFunMH: MethodHandle,
        actualClass: Class<out IndyStableChecker>,
        stableThread: Int,
        defaultMask: Long,
        defaultParamCount: Int,
    ): CallSite {
        val thread = stableThreadFromOrdinal(stableThread)
        val selfChecker = actualClass == SelfGenerateChecker::class.java
        val checkerMh = if (selfChecker) null else {
            val instance = getObjIns(lookup, actualClass)
            instance.checkerMethodHandle()
        }

        val generated = StableAsmGenerator(
            internalName = nextInternalName(lookup.lookupClass()),
            callType = type,
            stableThread = thread,
            isPure = false,
            useCheckerHandle = !selfChecker,
            defaultMask = defaultMask,
            defaultParamCount = defaultParamCount,
            loader = lookup.lookupClass().classLoader,
        ).generate()

        val classLookup = lookup.defineHiddenClass(
            generated.bytes,
            true,
            MethodHandles.Lookup.ClassOption.NESTMATE,
            MethodHandles.Lookup.ClassOption.STRONG,
        )
        val klass = classLookup.lookupClass()
        val invoke = classLookup.findStatic(klass, "invoke", generated.invokeType)
        val target = if (checkerMh == null) {
            MethodHandles.insertArguments(invoke, 0, actualFunMH)
        } else {
            MethodHandles.insertArguments(invoke, 0, actualFunMH, checkerMh)
        }.asType(type)
        return ConstantCallSite(target)
    }

    private fun stableThreadFromOrdinal(stableThread: Int): StableThread {
        val values = StableThread.values()
        return if (stableThread in values.indices) values[stableThread] else StableThread.SYNCHRONIZED
    }

    private fun nextInternalName(base: Class<*>): String {
        val id = counter.incrementAndGet()

        val baseClassPackage = base.`package`.name.replace('.', '/')

        return "$baseClassPackage/StableGen${id}"
    }
}
