package com.koala.koackpit

import android.content.Context

/**
 * Consommation moyenne apprise de la voiture, pour estimer l'autonomie (le boîtier CAN donne le carburant
 * en litres mais pas l'autonomie).
 *
 * Le niveau lu bouge de plusieurs litres selon la pente et les accélérations (carburant qui ballotte) : sur
 * 3 L consommés, l'erreur faisait passer la moyenne de 6,7 à 10 L/100 (constaté le 20/11 : 37 L → 360 km).
 * Donc : niveau lissé (médiane de la dernière minute), apprentissage seulement après 200 km et 10 L
 * consommés depuis le dernier plein, et valeur bornée à ce qu'une 1.9 TDI peut consommer (4,5 à 9 L/100).
 * Avant ça : la dernière moyenne fiable, ou 6 L/100 par défaut.
 */
object FuelStats {
    /** Réservoir de la Golf 5. */
    const val TANK_L = 55
    private const val DEFAULT_L100 = 6.0f
    private const val VERSION = 2

    private val recent = ArrayDeque<Int>()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("carburant", Context.MODE_PRIVATE).also { p ->
        // Version 1 : moyenne faussée possible, on repart de zéro
        if (p.getInt("version", 1) < VERSION) p.edit().clear().putInt("version", VERSION).apply()
    }

    /** Appelée à chaque relevé (chaque seconde). */
    fun update(ctx: Context, odometer: Int, fuel: Int) {
        if (odometer <= 0 || fuel <= 0) return
        recent.addLast(fuel)
        while (recent.size > 60) recent.removeFirst()
        if (recent.size < 30) return
        val smooth = recent.sorted()[recent.size / 2]
        val p = sp(ctx)
        val startOdo = p.getInt("odo0", -1)
        val startFuel = p.getInt("fuel0", -1)
        val lastFuel = p.getInt("fuel", -1)
        val e = p.edit()
        // Premier relevé, ou plein fait (niveau lissé remonté de 8 L ou plus) : nouveau point de départ
        if (startOdo < 0 || (lastFuel >= 0 && smooth >= lastFuel + 8) || odometer < startOdo) {
            e.putInt("odo0", odometer).putInt("fuel0", smooth)
        } else {
            val km = odometer - startOdo
            val used = startFuel - smooth
            if (km >= 200 && used >= 10) e.putFloat("l100", (used * 100f / km).coerceIn(4.5f, 9f))
        }
        if (smooth != lastFuel) e.putInt("fuel", smooth)
        e.apply()
    }

    /** Consommation retenue (L/100 km) et si elle est apprise (sinon valeur par défaut). */
    fun consumption(ctx: Context): Pair<Float, Boolean> {
        val v = sp(ctx).getFloat("l100", -1f)
        return if (v in 4.5f..9f) v to true else DEFAULT_L100 to false
    }

    /** Autonomie estimée en km, arrondie à la dizaine. */
    fun rangeKm(ctx: Context, fuel: Int): Int {
        val (l100, _) = consumption(ctx)
        return ((fuel * 100f / l100) / 10f).toInt() * 10
    }
}
