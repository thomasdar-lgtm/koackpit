package com.koala.koackpit

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs

/**
 * Rapport engagé (0.19.3), déduit du régime et de la vitesse (boîte manuelle 5 vitesses : rien sur le CAN).
 *
 * Chaque rapport a son « km/h pour 1 000 tr/min » ; au départ des valeurs types de la Golf 5 1.9 TDI, recalées
 * sur la 5e mesurée dans le journal (46,4 km/h à 1 000 tr/min). Ensuite Koackpit apprend les vraies valeurs de la
 * voiture (moyenne glissante quand le régime est stable), gardées d'un trajet à l'autre.
 * Embrayage enfoncé, point mort, patinage : aucun rapport ne correspond → « – ». À l'arrêt : rien.
 * Les infos arrivent une fois par seconde : l'affichage suit avec 1 à 2 s de retard.
 */
object Gear {
    /** Rapport affiché : 1..5, 0 = aucun ne correspond (« – »), null = à l'arrêt / pas d'infos. */
    val state = mutableStateOf<Int?>(null)

    private val defaults = floatArrayOf(8.8f, 16.1f, 26.3f, 36.8f, 46.4f)
    private var centers: FloatArray? = null
    private var candidate = -1; private var seen = 0
    private var lastRatio = 0f
    private var learnedSinceSave = 0
    private var logged = false

    private fun load(c: Context): FloatArray = centers ?: run {
        val p = c.getSharedPreferences("gear", Context.MODE_PRIVATE)
        FloatArray(5) { p.getFloat("g$it", defaults[it]) }.also { centers = it }
    }

    /** Rapport correspondant à un ratio (km/h pour 1 000 tr/min), ou -1. Tolérance ±9 %. */
    internal fun match(ratio: Float, cs: FloatArray): Int {
        var best = -1; var bestErr = 0.09f
        for (i in cs.indices) { val e = abs(ratio - cs[i]) / cs[i]; if (e < bestErr) { bestErr = e; best = i } }
        return best
    }

    /** Appelé à chaque trame d'infos (environ 1 par seconde). */
    fun feed(c: Context, rpm: Int, kmh: Float) {
        val cs = load(c)
        if (kmh < 4f || rpm < 700) { state.value = null; candidate = -1; seen = 0; lastRatio = 0f; return }
        val ratio = kmh / rpm * 1000f
        val g = match(ratio, cs)
        // Deux lectures de suite sur le même rapport pour changer l'affichage (évite les sauts embrayage/changement)
        if (g == candidate) seen++ else { candidate = g; seen = 1 }
        if (seen >= 2) state.value = if (g < 0) 0 else g + 1
        // Apprentissage : régime stable (ratio à ±2 % de la lecture précédente) et rapport reconnu
        if (g >= 0 && lastRatio > 0f && abs(ratio - lastRatio) / lastRatio < 0.02f) {
            cs[g] = cs[g] * 0.95f + ratio * 0.05f
            if (++learnedSinceSave >= 30) { save(c, cs); learnedSinceSave = 0 }
            if (!logged) { logged = true; WakeLog.add(c, "Rapports : reconnu ${g + 1}e à ${"%.1f".format(ratio)} km/h pour 1 000 tr/min") }
        }
        lastRatio = ratio
    }

    private fun save(c: Context, cs: FloatArray) {
        val e = c.getSharedPreferences("gear", Context.MODE_PRIVATE).edit()
        cs.forEachIndexed { i, v -> e.putFloat("g$i", v) }
        e.apply()
        WakeLog.add(c, "Rapports appris (km/h pour 1 000 tr/min) : " + cs.joinToString(" · ") { "%.1f".format(it) })
    }
}
