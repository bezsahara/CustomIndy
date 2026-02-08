package org.bezsahara.customindy.annotations

import java.lang.invoke.MethodHandle
import kotlin.reflect.KClass

/**
 * Provides a MethodHandle used to decide whether cached arguments changed.
 *
 * The MethodHandle must accept doubled arguments (old/new pairs in definition order)
 * and return Boolean where `true` means "changed".
 */
public interface IndyStableChecker {
    // MethodHandle must accept doubled arguments (old/new pairs in order) and return Boolean (true = changed).
    public fun checkerMethodHandle(): MethodHandle
}

/**
 * Marker for the built-in checker generator.
 *
 * When used, the compiler/plugin generates an argument comparator that uses
 * `Objects.equals` for objects and `Arrays.equals` for one-dimensional arrays.
 */
public object SelfGenerateChecker : IndyStableChecker {
    override fun checkerMethodHandle(): MethodHandle {
        error("Compiler or bsm must custom generate!")
    }
}

/**
 * Caches a call site result and recomputes only when arguments change.
 *
 * The cache is per call site. If [checker] is [SelfGenerateChecker], the plugin
 * generates the argument comparator. Otherwise, the provided checker is used.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
public annotation class IndyStable(
    val checker: KClass<out IndyStableChecker> = SelfGenerateChecker::class,
    val stableThread: StableThread = StableThread.SYNCHRONIZED,
)

/**
 * Caches the first computed result forever and ignores arguments.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
public annotation class IndyStablePure(val stableThread: StableThread = StableThread.SYNCHRONIZED)

/**
 * Controls locking behavior for stable call sites.
 */
public enum class StableThread {
    SYNCHRONIZED,
    NONE
}
