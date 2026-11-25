package com.koala.koackpit

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Essai du rapport engagé (boîte DSG 6) avec les relevés réels du journal et des changements de rapport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotsGearCheck {
    @Test
    fun rapports() {
        val c = RuntimeEnvironment.getApplication()
        val out = StringBuilder()
        fun run(rpm: Int, kmh: Float, times: Int = 3): Int? { repeat(times) { Gear.feed(c, rpm, kmh) }; out.append("$rpm tr/min, $kmh km/h → ${Gear.state.value}\n"); return Gear.state.value }
        // Relevés réels du journal (23-24/11)
        assertEquals(6, run(2372, 109.9f))   // 46,3 → 6e
        assertEquals(5, run(1695, 65.3f))    // 38,5 → 5e
        assertEquals(4, run(1509, 45.6f))    // 30,2 → 4e
        assertEquals(4, run(971, 28.4f))     // 29,2 → 4e
        assertEquals(3, run(863, 17.6f))     // 20,4 → 3e
        assertEquals(2, run(1134, 14.9f))    // 13,1 → 2e
        assertEquals(0, run(1869, 28.1f))    // 15,0 : changement de rapport → « – »
        // Calculés
        assertEquals(1, run(1500, 11.7f))    // 7,8 → 1re
        assertEquals(null, run(800, 2f))     // à l'arrêt → rien
        // Une seule lecture d'un autre rapport ne change pas l'affichage
        run(1695, 65.3f); Gear.feed(c, 2372, 109.9f)
        out.append("après une seule lecture de 6e : ${Gear.state.value}\n")
        assertEquals(5, Gear.state.value)
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/rapport-essai.txt").writeText(out.toString())
    }
}
