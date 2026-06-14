package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ICanHealthSingletons] eager warm-up.
 *
 * The warm-up forces every Kotlin `object` in the iCan driver to run its
 * `<clinit>` and bind its `INSTANCE` field before the first Compose
 * measure that could reach them. These tests assert that the warm-up:
 *
 *  - returns a non-empty set of class names,
 *  - touches every `object` we expect it to touch,
 *  - is safe to call multiple times,
 *  - is safe to call from any thread,
 *  - reports the same class names via [ICanHealthSingletons.warmedClassNames]
 *    and [ICanHealthSingletons.isWarmedClass].
 */
class ICanHealthSingletonsTests {

    @Test
    fun ensureInitialized_returnsAllExpectedWarmedClasses() {
        val touched = ICanHealthSingletons.ensureInitialized()
        val expected = ICanHealthSingletons.warmedClassNames()
        assertEquals(
            "ensureInitialized should touch every warmed class",
            expected.toSet(),
            touched.toSet(),
        )
    }

    @Test
    fun ensureInitialized_touchesEveryIcanObject() {
        val touched = ICanHealthSingletons.ensureInitialized().toSet()
        // Sanity-check that each iCan `object` we care about is covered.
        assertTrue("ICanHealthConstants must be warmed", ICanHealthConstants::class.java.name in touched)
        assertTrue("ICanHealthProfileResolver must be warmed", ICanHealthProfileResolver::class.java.name in touched)
        assertTrue("ICanHealthCrypto must be warmed", ICanHealthCrypto::class.java.name in touched)
        assertTrue("ICanHealthParser must be warmed", ICanHealthParser::class.java.name in touched)
        assertTrue("ICanHealthCeCalibration must be warmed", ICanHealthCeCalibration::class.java.name in touched)
        assertTrue("ICanHealthManagedSensorIdentityAdapter must be warmed", ICanHealthManagedSensorIdentityAdapter::class.java.name in touched)
        assertTrue("ICanHealthRegistry must be warmed", ICanHealthRegistry::class.java.name in touched)
    }

    @Test
    fun ensureInitialized_isIdempotent() {
        val first = ICanHealthSingletons.ensureInitialized()
        val second = ICanHealthSingletons.ensureInitialized()
        val third = ICanHealthSingletons.ensureInitialized()
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun ensureInitialized_isThreadSafe() {
        // Run the warm-up from many threads concurrently. The JVM caches
        // class-init results per ClassLoader, so all calls must observe
        // the same set of warmed classes.
        val threads = (0 until 8).map { idx ->
            Thread {
                repeat(50) {
                    val warmed = ICanHealthSingletons.ensureInitialized()
                    assertEquals(
                        "Thread $idx saw a partial warm-up",
                        ICanHealthSingletons.warmedClassNames().toSet(),
                        warmed.toSet(),
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun warmedClassNames_isNotEmpty() {
        val names = ICanHealthSingletons.warmedClassNames()
        assertNotNull(names)
        assertTrue("warm-up list should be non-empty", names.isNotEmpty())
    }

    @Test
    fun isWarmedClass_returnsTrueForKnownClasses() {
        assertTrue(ICanHealthSingletons.isWarmedClass(ICanHealthConstants::class.java.name))
        assertTrue(ICanHealthSingletons.isWarmedClass(ICanHealthRegistry::class.java.name))
    }

    @Test
    fun isWarmedClass_returnsFalseForUnrelatedClasses() {
        assertFalse(ICanHealthSingletons.isWarmedClass("java.lang.String"))
        assertFalse(ICanHealthSingletons.isWarmedClass("not.a.real.Class"))
        assertFalse(ICanHealthSingletons.isWarmedClass(""))
    }

    @Test
    fun warmedIcanObjects_haveNonNullInstanceFields() {
        // After the warm-up, the Kotlin `object` singletons' `INSTANCE`
        // fields must be bound. Reading them should never return null.
        ICanHealthSingletons.ensureInitialized()

        val instanceFields = listOf(
            ICanHealthConstants::class.java,
            ICanHealthProfileResolver::class.java,
            ICanHealthCrypto::class.java,
            ICanHealthParser::class.java,
            ICanHealthCeCalibration::class.java,
            ICanHealthManagedSensorIdentityAdapter::class.java,
            ICanHealthRegistry::class.java,
        )
        for (cls in instanceFields) {
            val instance = cls.getDeclaredField("INSTANCE").get(null)
            assertNotNull("${cls.name}.INSTANCE should be bound after warm-up", instance)
        }
    }
}
