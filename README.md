# CustomIndy

CustomIndy is a Kotlin K2 compiler plugin that lets you express JVM `invokedynamic` call sites with annotations.
You mark a function with `@CustomIndy` to define the bootstrap method, and annotate the call expression with
`@Indy*` annotations to provide ordered bootstrap arguments. The plugin rewrites those calls into
`invokedynamic` instructions during compilation.

## What it does

- Collects `@Indy*` annotations at call sites during FIR and validates their indices.
- Rewrites calls to `@CustomIndy` functions in IR to a JVM `invokedynamic`.
- Emits the bootstrap method handle and arguments into the `invokedynamic` instruction.

## Modules

- `compiler-plugin`: the compiler plugin implementation (FIR + IR + JVM backend intrinsic).
- `plugin-annotations`: annotations used from user code (`@CustomIndy`, `@IndyInt`, etc.).
- `gradle-plugin`: Gradle plugin that wires the compiler plugin and annotations into builds.

## Usage (annotations)

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
    bsmDesc = $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;"
)
fun greet(): String = "fallback"

fun use(): String =
    @IndyInt(index = 0, value = 7)
    greet()
```

## Usage (Gradle)

Apply the Gradle plugin in the consumer project:

```kotlin
plugins {
    id("org.bezsahara.customindy") version "0.1.2"
}
```

The Gradle plugin:
- adds the compiler plugin artifact,
- adds the annotations dependency to each Kotlin compilation.

## Notes and limitations

- Only functions without a dispatch receiver are rewritten (top-level or `@JvmStatic`).
- `@Indy*` indices must be contiguous from 0 with no duplicates.
- `IndyMethodHandle` is currently treated as `INVOKESTATIC`.

## Development

Everyone is welcome to contribute.

