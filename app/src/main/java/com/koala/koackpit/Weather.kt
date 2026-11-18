package com.koala.koackpit

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Météo de la route (Open-Meteo, gratuit, sans compte) à la position GPS de l'autoradio :
 * temps actuel, pluie dans les 2 prochaines heures (par quart d'heure) et les 4 prochaines heures.
 * Rafraîchie toutes les 10 min, ou dès 5 km parcourus.
 */
object Weather {
    data class Hour(val label: String, val code: Int, val temp: Int, val rain: Int)
    data class Forecast(val code: Int, val temp: Int, val rainNote: String, val rainSoon: Boolean, val hours: List<Hour>)

    val state = mutableStateOf<Forecast?>(null)
    private var at = 0L
    private var where: Location? = null
    @Volatile private var busy = false

    /** Appelée chaque minute par le service. */
    fun tick(ctx: Context) {
        if (busy) return
        val loc = lastLocation(ctx) ?: return
        val moved = where?.distanceTo(loc)?.let { it > 5000f } ?: true
        if (!moved && SystemClock.elapsedRealtime() - at < 10 * 60_000L) return
        busy = true
        Thread {
            try {
                val f = fetch(loc.latitude, loc.longitude)
                state.value = f
                at = SystemClock.elapsedRealtime(); where = loc
            } catch (_: Exception) {
            } finally { busy = false }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun fetch(lat: Double, lon: Double): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code&minutely_15=precipitation&forecast_minutely_15=8" +
            "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=6&timezone=auto"
        val txt = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000
            try { inputStream.bufferedReader().readText() } finally { disconnect() }
        }
        return parse(JSONObject(txt))
    }

    fun parse(js: JSONObject): Forecast {
        val cur = js.getJSONObject("current")
        val m = js.optJSONObject("minutely_15")
        val h = js.getJSONObject("hourly")
        // Pluie : premier quart d'heure avec au moins 0,1 mm dans les 2 heures
        var note = ""
        var soon = false
        if (m != null) {
            val t = m.getJSONArray("time"); val p = m.getJSONArray("precipitation")
            for (i in 0 until minOf(t.length(), p.length())) {
                if (p.optDouble(i, 0.0) >= 0.1) {
                    note = if (i == 0) "Pluie en cours" else "Pluie vers ${t.getString(i).substringAfter('T').take(5)}"
                    soon = true
                    break
                }
            }
        }
        val ht = h.getJSONArray("time"); val hc = h.getJSONArray("weather_code")
        val htemp = h.getJSONArray("temperature_2m"); val hp = h.getJSONArray("precipitation_probability")
        val hours = (1 until minOf(5, ht.length())).map { i ->
            Hour(ht.getString(i).substringAfter('T').take(2) + " h", hc.optInt(i), Math.round(htemp.optDouble(i)).toInt(), hp.optInt(i))
        }
        if (note.isEmpty()) {
            val wet = hours.firstOrNull { it.rain >= 50 }
            note = if (wet != null) "Pluie probable vers ${wet.label}" else "Pas de pluie prévue"
        }
        return Forecast(cur.optInt("weather_code"), Math.round(cur.optDouble("temperature_2m")).toInt(), note, soon, hours)
    }

    /** Pictogramme et libellé des codes météo WMO. */
    fun icon(code: Int): String = when (code) {
        0 -> "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"
        in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"
        else -> "☁️"
    }

    fun label(code: Int): String = when (code) {
        0 -> "Ensoleillé"; 1 -> "Peu nuageux"; 2 -> "Éclaircies"; 3 -> "Couvert"; 45, 48 -> "Brouillard"
        in 51..57 -> "Bruine"; in 61..65 -> "Pluie"; 66, 67 -> "Pluie verglaçante"; in 71..77 -> "Neige"
        in 80..82 -> "Averses"; 85, 86 -> "Averses de neige"; in 95..99 -> "Orage"
        else -> "Nuageux"
    }
}
