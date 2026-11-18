package com.koala.koackpit

import android.content.Context

/**
 * Consommation moyenne apprise de la voiture, pour estimer l'autonomie (le boîtier CAN donne le carburant
 * en litres mais pas l'autonomie). Point de départ = dernier plein (le carburant remonte de 5 L ou plus) ;
 * consommation = litres consommés / km parcourus depuis, dès 50 km et 3 L. Avant : valeur par défaut.
 */
object FuelStats {
    /** Réservoir de la Golf 5. */
    const val TANK_L = 55
    private const val DEFAULT_L100 = 6.5f

    private fun sp(ctx: Context) = ctx.getSharedPreferences("carburant", Context.MODE_PRIVATE)

    /** Appelée à chaque relevé (chaque seconde) : ne garde que l'essentiel. */
    fun update(ctx: Context, odometer: Int, fuel: Int) {
        if (odometer <= 0 || fuel <= 0) return
        val p = sp(ctx)
        val startOdo = p.getInt("odo0", -1)
        val startFuel = p.getInt("fuel0", -1)
        val lastFuel = p.getInt("fuel", -1)
        val e = p.edit()
        // Premier relevé, ou plein fait : nouveau point de départ (on garde la dernière moyenne connue)
        if (startOdo < 0 || (lastFuel >= 0 && fuel >= lastFuel + 5) || odometer < startOdo) {
            e.putInt("odo0", odometer).putInt("fuel0", fuel)
        } else {
            val km = odometer - startOdo
            val used = startFuel - fuel
            if (km >= 50 && used >= 3) e.putFloat("l100", used * 100f / km)
        }
        if (fuel != lastFuel) e.putInt("fuel", fuel)
        e.apply()
    }

    /** Consommation retenue (L/100 km) et si elle est apprise (sinon valeur par défaut). */
    fun consumption(ctx: Context): Pair<Float, Boolean> {
        val v = sp(ctx).getFloat("l100", -1f)
        return if (v in 3f..20f) v to true else DEFAULT_L100 to false
    }

    /** Autonomie estimée en km, arrondie à la dizaine. */
    fun rangeKm(ctx: Context, fuel: Int): Int {
        val (l100, _) = consumption(ctx)
        return ((fuel * 100f / l100) / 10f).toInt() * 10
    }
}
