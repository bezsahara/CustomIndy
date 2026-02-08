package org.bezsahara.customindy.annotations

/**
 * Bootstrap interface used by `@SimpleIndy`.
 *
 * The compiler plugin emits `invokedynamic` that calls `AutoBootstrap.bootstrap`,
 * which instantiates the implementation (object or no-arg constructor) and then
 * forwards to this method.
 *
 * `args` are the ordered bootstrap arguments collected from `@Indy*` annotations
 * at the call site (0-based indices, contiguous).
 *
 * The returned CallSite target must match the provided [type].
 */
public interface BootstrapIndy {
    public fun bootstrap(
        lookup: java.lang.invoke.MethodHandles.Lookup,
        name: String,
        type: java.lang.invoke.MethodType,
        args: Array<out Any>
    ): java.lang.invoke.CallSite
}
