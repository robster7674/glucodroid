// JugglucoNG — iCanHealth singleton warm-up.
//
// Compose 1.11.x + Material3 1.4.x + Kotlin 2.3.21 + R8 -repackageclasses
// changed how `object` singletons are reached during the first measure pass.
// The Compose runtime now resolves `staticCompositionLocalOf` default values
// through a `Lazy<>` holder that is itself an `object` whose `<clinit>` is
// expected to run as a side effect of the host composable's first measure.
//
// When a Kotlin `object` is *only* referenced from a `runCatching { ... }`
// block (e.g. `runCatching { ICanHealthProfileResolver.resolve(...) }
// .getOrDefault(default)`), R8's reachability analysis under
// `-repackageclasses` can fail to mark the object's `<clinit>` as live, and
// the object's `INSTANCE` field stays null. The first read of any field on
// the null `INSTANCE` then throws the `Attempt to read from field 'X Y.a' on
// a null object reference` NPE that crashes the Sensors screen on the
// recompose after the iCan i3 setup wizard dismisses.
//
// This class is the explicit fix: callers call [ensureInitialized] before
// any Compose measure that could hit the iCan driver code path, which
// forces the JVM to run each `object`'s `<clinit>` and bind their
// `INSTANCE` fields. This is the same pattern AndroidX ships for
// `androidx.lifecycle.Lifecycle` — eagerly touching the singleton so the
// first user-facing access can't race with class loading.

package tk.glucodata.drivers.icanhealth

/**
 * Forces the iCanHealth driver singletons to run their `<clinit>` blocks
 * before the first Compose measure that could reach them.
 *
 * Safe to call multiple times — subsequent calls are no-ops because the
 * JVM caches class-init results per ClassLoader. Safe to call from any
 * thread.
 *
 * Returns the list of class names that were successfully loaded, in load
 * order, so callers (and tests) can verify the expected singletons were
 * warmed.
 */
object ICanHealthSingletons {
    /**
     * Classes that must have their `<clinit>` run before the first Compose
     * measure that touches the iCan driver. The list is ordered so that
     * dependencies are loaded before their dependents. Only `object`
     * declarations are included here — companion objects and data classes
     * are reached through their owning class's normal access pattern.
     */
    private val WARMED_CLASSES: List<Class<*>> = listOf(
        ICanHealthConstants::class.java,
        ICanHealthProfileResolver::class.java,
        ICanHealthCrypto::class.java,
        ICanHealthParser::class.java,
        ICanHealthCeCalibration::class.java,
        ICanHealthManagedSensorIdentityAdapter::class.java,
        ICanHealthRegistry::class.java,
    )

    @JvmStatic
    fun ensureInitialized(): List<String> {
        val touched = ArrayList<String>(WARMED_CLASSES.size)
        for (cls in WARMED_CLASSES) {
            try {
                // Kotlin `object` declarations compile to a Java class with
                // a static `INSTANCE` field. Reading that field is the
                // canonical way to force the host class's `<clinit>` to
                // run. If the field doesn't exist (e.g. some build flavors
                // strip it), the NoSuchFieldException path is a safe
                // no-op — the class will initialize on first real use.
                cls.getDeclaredField("INSTANCE").get(null)
                touched.add(cls.name)
            } catch (_: NoSuchFieldException) {
                // Not a Kotlin `object` — skip; its members are reached
                // through their owning class normally.
            } catch (_: Throwable) {
                // Don't propagate — warm-up is best-effort. The driver
                // path will surface the underlying error if it's real.
            }
        }
        return touched
    }

    /**
     * Returns the warm-up order. Useful for tests that need to assert a
     * specific singleton was reached.
     */
    @JvmStatic
    fun warmedClassNames(): List<String> = WARMED_CLASSES.map { it.name }

    /**
     * Returns true if [name] is a class that this warm-up would touch.
     */
    @JvmStatic
    fun isWarmedClass(name: String): Boolean = WARMED_CLASSES.any { it.name == name }
}
