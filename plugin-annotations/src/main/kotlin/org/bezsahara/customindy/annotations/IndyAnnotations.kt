@file:Suppress("SpellCheckingInspection")

package org.bezsahara.customindy.annotations

import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget
import kotlin.reflect.KClass

/**
 * Method handle kinds backed by ASM handle tags.
 *
 * These values map directly to `Opcodes.H_*` and are used to build
 * method-handle constants for invokedynamic bootstrap methods.
 */
public enum class MHKind(public val tag: Int) {
    GETFIELD(1),
    GETSTATIC(2),
    PUTFIELD(3),
    PUTSTATIC(4),
    INVOKEVIRTUAL(5),
    INVOKESTATIC(6),  // <- The only one that was tested, tho others supposed to work
    INVOKESPECIAL(7),
    NEWINVOKESPECIAL(8),
    INVOKEINTERFACE(9),
}

//int H_GETFIELD = 1;
//int H_GETSTATIC = 2;
//int H_PUTFIELD = 3;
//int H_PUTSTATIC = 4;
//int H_INVOKEVIRTUAL = 5;
//int H_INVOKESTATIC = 6;
//int H_INVOKESPECIAL = 7;
//int H_NEWINVOKESPECIAL = 8;
//int H_INVOKEINTERFACE = 9;

/**
 * Adds an `Int` bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyInt(val index: Int, val value: Int)

/**
 * Adds a `Long` bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyLong(val index: Int, val value: Long)

/**
 * Adds a `Double` bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyDouble(val index: Int, val value: Double)

/**
 * Adds a `String` bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyStr(val index: Int, val value: String)

/**
 * Adds a `Class`/`KClass` bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyClass(val index: Int, val value: KClass<*>)

/**
 * Adds a method-handle bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * [owner] is the declaring class, [name] is the member name, and [desc] is the JVM descriptor.
 * The compiler plugin currently treats this handle as `INVOKESTATIC`.
 *
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyMethodHandle(
    val index: Int,
    val owner: KClass<*>,
    val name: String,
    val desc: String
)

/**
 * Adds a method-type bootstrap argument at [index] for the surrounding `@CustomIndy` call expression.
 *
 * [desc] is the JVM method descriptor used to construct the `MethodType` constant.
 * Indices must be contiguous from 0 with no duplicates.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
@Repeatable
public annotation class IndyMethodType(
    val index: Int,
    val desc: String
)

/**
 * Marks a function as a custom invokedynamic call site.
 *
 * When the compiler plugin is enabled, calls to this function are rewritten to an `invokedynamic`
 * instruction using the bootstrap method described by [bsmKind], [bsmOwner], [bsmName], and [bsmDesc].
 * Any `@Indy*` annotations placed on the call expression become bootstrap arguments.
 *
 * The invokedynamic "name" and "descriptor" are derived from the annotated function itself:
 * the call site name is the function name, and the call site descriptor is the JVM descriptor
 * of the function signature. The bootstrap method returns a CallSite whose target must match
 * that descriptor.
 *
 * How the data flows:
 * - FIR collects `@Indy*` annotations from the call expression, validates indices, and records
 *   the ordered bootstrap arguments for that call site.
 * - IR rewriting replaces the call with an invokedynamic, passing:
 *   - the call site name/descriptor from the annotated function,
 *   - the bootstrap method handle from [bsmKind]/[bsmOwner]/[bsmName]/[bsmDesc],
 *   - the recorded bootstrap arguments in index order.
 *
 * Example:
 * ```kotlin
 * object Bootstrap {
 *     @JvmStatic
 *     fun bootstrap(
 *         lookup: java.lang.invoke.MethodHandles.Lookup,
 *         name: String,
 *         type: java.lang.invoke.MethodType,
 *         id: Int
 *     ): java.lang.invoke.CallSite {
 *         val mh = java.lang.invoke.MethodHandles.constant(String::class.java, "id=$id")
 *         return java.lang.invoke.ConstantCallSite(mh.asType(type))
 *     }
 * }
 *
 * @CustomIndy(
 *     bsmKind = MHKind.INVOKESTATIC,
 *     bsmOwner = Bootstrap::class,
 *     bsmName = "bootstrap",
 *     bsmDesc = $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;"
 * )
 * fun greet(): String = "fallback"
 *
 * fun use(): String =
 *     @IndyInt(index = 0, value = 7)
 *     greet()
 * // invokedynamic greet()Ljava/lang/String; with bootstrap arg [7]
 * ```
 *
 * Only functions without a dispatch receiver are rewritten (top-level or `@JvmStatic`).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class CustomIndy(
    val bsmKind: MHKind,
    val bsmOwner: KClass<*>,
    val bsmName: String,
    val bsmDesc: String
)
