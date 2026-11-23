package com.koala.koackpit

import android.location.Location
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Essai des zones de danger sans la voiture : trajet simulé vers des radars de la liste officielle.
 * Le compte rendu est écrit dans build/screens/radar-essai.txt (publié avec les captures).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenshotsRadarCheck {
    private val out = StringBuilder()

    /** Roule en ligne droite (cap [bearing]) de [startM] mètres avant le point jusqu'à [endM] mètres après, à [kmh]. */
    private fun drive(lat: Double, lon: Double, bearing: Float, kmh: Int, startM: Int, endM: Int): List<Pair<Int, Radar.Alert?>> {
        val ctx = RuntimeEnvironment.getApplication()
        val r = ArrayList<Pair<Int, Radar.Alert?>>()
        val b = Math.toRadians(bearing.toDouble())
        var m = -startM
        while (m <= endM) {
            val l = Location("test").apply {
                latitude = lat + m * Math.cos(b) / 110_540.0
                longitude = lon + m * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(lat)))
                this.bearing = bearing; speed = kmh / 3.6f; time = System.currentTimeMillis()
            }
            Radar.tick(ctx, l)
            r.add(m to Radar.alert.value)
            m += 25
        }
        return r
    }

    private fun summary(name: String, r: List<Pair<Int, Radar.Alert?>>): Pair<Int?, Int?> {
        val on = r.filter { it.second != null }
        val first = on.firstOrNull()?.first; val last = on.lastOrNull()?.first
        out.append("$name : zone de ${first ?: "-"} m à ${last ?: "-"} m (radar à 0 m)")
        on.firstOrNull()?.second?.let { out.append(", ${it.kind}, ${it.vma ?: "-"}, reste ${it.leftM} m au début, excès=${it.over}") }
        out.append("\n")
        return first to last
    }

    @Test
    fun zones() {
        val lines = java.io.File("src/main/assets/radars.txt").readLines()
        out.append("Radars chargés : ${Radar.loadFrom(lines)}\n")
        // ETD 80 près de Saint-Geours (43.711, -1.2282) : zone 2 km attendue
        val a = summary("ETD 80, vers le nord à 78", drive(43.711, -1.2282, 0f, 78, 3000, 3000))
        assertTrue(a.first != null && a.first!! < 0 && a.last!! > 0)
        assertTrue(a.last!! - a.first!! in 1800..2100)
        Radar.loadFrom(lines)
        summary("ETD 80, vers le nord à 95 (excès)", drive(43.711, -1.2282, 0f, 95, 3000, 3000))
        Radar.loadFrom(lines)
        // Cap perpendiculaire, à 1 km du radar : pas d'alerte
        val c = summary("route parallèle à 1 km", drive(43.711, -1.2282 + 1000 / (111_320.0 * Math.cos(Math.toRadians(43.711))), 0f, 78, 3000, 3000))
        assertTrue(c.first == null)
        // Feu rouge (premier ETFR de la liste) : zone 300 m
        Radar.loadFrom(lines)
        val fr = lines.first { it.startsWith("ETFR;") }.split(';')
        val f = summary("feu rouge ${fr[2]},${fr[3]} à 45", drive(fr[2].toDouble(), fr[3].toDouble(), 90f, 45, 800, 800))
        assertTrue(f.first != null && f.last!! - f.first!! in 250..350)
        // Voiture arrêtée : rien
        Radar.loadFrom(lines)
        val s = summary("arrêté (5 km/h)", drive(43.711, -1.2282, 0f, 5, 3000, 3000))
        assertTrue(s.first == null)
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/radar-essai.txt").writeText(out.toString())
    }
}
