# CustomIndy

CustomIndy is a Kotlin K2 compiler plugin that lets you express JVM `invokedynamic` call sites with annotations.
You mark a function with `@CustomIndy` to define the bootstrap method, and annotate the call expression with
`@Indy*` annotations to provide ordered bootstrap arguments. The plugin rewrites those calls into
`invokedynamic` instructions during compilation.

## What it does

- Rewrites calls to `@CustomIndy` functions in IR to a JVM `invokedynamic`.
- Emits the bootstrap method handle and arguments into the `invokedynamic` instruction.
- Supports `@SimpleIndy` via `AutoBootstrap` for a smaller user-facing API.
- Supports stable call-site caching with `@IndyStable` and `@IndyStablePure`.

## Modules

- `compiler-plugin`: the compiler plugin implementation (FIR + IR + JVM backend intrinsic).
- `plugin-annotations`: annotations used from user code (`@CustomIndy`, `@IndyInt`, etc.).
- `gradle-plugin`: Gradle plugin that wires the compiler plugin and annotations into builds.

## Usage

### SimpleIndy

```kotlin
object SimpleBootstrap : BootstrapIndy {
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

fun useSimple(): String =
    @IndyInt(index = 0, value = 3)
    @IndyStr(index = 1, value = "ok")
    greetSimple()
```

### CustomIndy

```kotlin
object Bootstrap {
    @JvmStatic
    fun bootstrap(
        lookup: java.lang.invoke.MethodHandles.Lookup,
        name: String,
        type: java.lang.invoke.MethodType,
        id: Int
    ): java.lang.invoke.CallSite {
        val mh = java.lang.invoke.MethodHandles.constant(String::class.java, "id=$id")
        return java.lang.invoke.ConstantCallSite(mh.asType(type))
    }
}

@CustomIndy(
    bsmKind = MHKind.INVOKESTATIC,
    bsmOwner = Bootstrap::class,
    bsmName = "bootstrap",
    bsmDesc = "(Ljava/lang/invoke/MethodHandles\\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;"
)
fun greet(): String = "fallback"

fun use(): String =
    @IndyInt(index = 0, value = 7)
    greet()
```

### Stable values

`@IndyStable` caches results per call site and reuses the cached value when arguments are unchanged.
`@IndyStablePure` caches the first result forever and ignores arguments.
The default checker (`SelfGenerateChecker`) for `@IndyStable` compares each argument with `Objects.equals`, and uses
`Arrays.equals` for arrays (single-dimension only).
Use `StableThread.SYNCHRONIZED` for a safe lock around update; `StableThread.NONE` does no locking.

```kotlin
fun computeStable(x: Int): Int = x * 10
fun computePure(x: Int): Int = x * 100

fun useStable(x: Int): Int = @IndyStable(stableThread = StableThread.SYNCHRONIZED) computeStable(x)
fun usePure(x: Int): Int = @IndyStablePure computePure(x)

// Optional custom checker: returns true when arguments changed.
object MyChecker : IndyStableChecker {
    @JvmStatic fun check(oldX: Int, newX: Int): Boolean = oldX != newX
    override fun checkerMethodHandle(): java.lang.invoke.MethodHandle =
        java.lang.invoke.MethodHandles.lookup().findStatic(
            MyChecker::class.java,
            "check",
            java.lang.invoke.MethodType.methodType(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        )
}

fun useStableCustom(x: Int): Int =
    @IndyStable(checker = MyChecker::class, stableThread = StableThread.NONE) computeStable(x)
```

Notes:
- Custom checker MH must accept doubled arguments (old/new pairs in definition order) and return `Boolean`
  (`true` means changed).
- Multi-dimensional arrays are not deep-compared by the default checker; use a custom checker if needed.

## Usage (Gradle)

Apply the Gradle plugin in the consumer project:

```kotlin
plugins {
    id("org.bezsahara.customindy")
}
```

The Gradle plugin:
- adds the compiler plugin artifact,
- adds the annotations dependency to each Kotlin compilation.

## Notes and limitations

- Only functions without a dispatch receiver are rewritten (top-level or `@JvmStatic`).
- `@Indy*` indices must be contiguous from 0 with no duplicates.
- Default arguments are not supported for `@CustomIndy` and `@SimpleIndy` call sites.
- `IndyMethodHandle` is currently treated as `INVOKESTATIC`.

## Development

Everyone is welcome to contribute.
