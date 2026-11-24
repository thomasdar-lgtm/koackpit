package com.koala.koackpit

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Essai du rapport engagé avec les relevés réels du journal (diagnostic du 24/11) et des cas d'embrayage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotsGearCheck {
    @Test
    fun rapports() {
        val c = RuntimeEnvironment.getApplication()
        val out = StringBuilder()
        fun run(rpm: Int, kmh: Float, times: Int = 3): Int? { repeat(times) { Gear.feed(c, rpm, kmh) }; out.append("$rpm tr/min, $kmh km/h → ${Gear.state.value}\n"); return Gear.state.value }
        assertEquals(5, run(2372, 109.9f))   // relevé réel 23/11 (5e)
        assertEquals(4, run(1695, 65.3f))    // relevé réel 24/11 (38,5 → 4e)
        assertEquals(2, run(1100, 17.6f))    // 16 → 2e
        assertEquals(1, run(1500, 13.2f))    // 8,8 → 1re
        assertEquals(3, run(2000, 52.6f))    // 26,3 → 3e
        assertEquals(0, run(3000, 60f))      // 20 : entre 2e et 3e (embrayage) → « – »
        assertEquals(null, run(800, 2f))     // à l'arrêt → rien
        // Une seule lecture d'un autre rapport ne change pas l'affichage
        run(1695, 65.3f); Gear.feed(c, 2372, 109.9f)
        out.append("après une seule lecture de 5e : ${Gear.state.value}\n")
        assertEquals(4, Gear.state.value)
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/rapport-essai.txt").writeText(out.toString())
    }
}
