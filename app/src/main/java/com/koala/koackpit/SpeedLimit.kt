package com.koala.koackpit

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos

/**
 * Limitation de vitesse de la route où l'on roule, d'après OpenStreetMap (service Overpass, gratuit).
 * Les routes autour (1 km) sont téléchargées d'un coup puis la route actuelle est retrouvée sur place à chaque
 * position GPS : la plus proche, dans le sens de la marche. Nouveau téléchargement après 600 m.
 * Route sans limitation renseignée : rien n'est affiché (pas de valeur inventée).
 */
object SpeedLimit {
    val limit = mutableStateOf<Int?>(null)

    private class Way(val limit: Int?, val pts: DoubleArray)   // lat, lon, lat, lon…
    @Volatile private var ways: List<Way> = emptyList()
    @Volatile private var center: Location? = null
    @Volatile private var busy = false
    private var lastFetch = 0L
    private var lm: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (lm != null) return
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()); lm = m }
    }

    fun stop() {
        runCatching { lm?.removeUpdates(listener) }
        lm = null
    }

    /** Position du téléphone (plus précise) : utilisée en priorité quand elle arrive. */
    fun onPhoneLocation(loc: Location) { phoneAt = SystemClock.elapsedRealtime(); handle(loc) }
    private var phoneAt = 0L

    private fun onLocation(loc: Location) {
        // GPS de l'autoradio seulement si le téléphone ne donne pas sa position
        if (SystemClock.elapsedRealtime() - phoneAt < 4000) return
        handle(loc)
    }

    private fun handle(loc: Location) {
        val c = center
        val now = SystemClock.elapsedRealtime()
        if (!busy && (c == null || c.distanceTo(loc) > 600f) && now - lastFetch > 15_000) {
            busy = true; lastFetch = now
            Thread {
                try { ways = fetch(loc.latitude, loc.longitude); center = loc } catch (_: Exception) {} finally { busy = false }
            }.start()
        }
        limit.value = match(loc)
    }

    /** Route la plus proche (moins de 25 m), en écartant celles qui partent dans une autre direction. */
    private fun match(loc: Location): Int? {
        val ws = ways
        if (ws.isEmpty()) return null
        val lat0 = loc.latitude; val lon0 = loc.longitude
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        for (w in ws) {
            val p = w.pts
            var i = 0
            while (i + 3 < p.size) {
                val ax = (p[i + 1] - lon0) * kx; val ay = (p[i] - lat0) * ky
                val bx = (p[i + 3] - lon0) * kx; val by = (p[i + 2] - lat0) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val px = ax + t * dx; val py = ay + t * dy
                var score = Math.sqrt(px * px + py * py)
                if (score < 25 && moving && len2 > 4) {
                    val seg = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 180
                    val dir = (loc.bearing.toDouble() + 360) % 180
                    val diff = abs(seg - dir).let { if (it > 90) 180 - it else it }
                    if (diff > 35) score += 40   // route qui croise : seulement si rien de mieux
                }
                if (score < bestScore) { bestScore = score; best = w }
                i += 2
            }
        }
        return if (bestScore < 25) best?.limit else null
    }

    private fun fetch(lat: Double, lon: Double): List<Way> {
        val q = "[out:json][timeout:20];way(around:1000,%.5f,%.5f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|service)$\"];out tags geom;"
            .format(java.util.Locale.US, lat, lon)
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        val txt = try {
            conn.connectTimeout = 10_000; conn.readTimeout = 25_000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Koackpit/${BuildConfig.VERSION_NAME}")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
        val els = JSONObject(txt).getJSONArray("elements")
        val out = ArrayList<Way>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val tags = e.optJSONObject("tags")
            val pts = DoubleArray(g.length() * 2)
            for (j in 0 until g.length()) { val o = g.getJSONObject(j); pts[2 * j] = o.getDouble("lat"); pts[2 * j + 1] = o.getDouble("lon") }
            out.add(Way(parse(tags?.optString("maxspeed")), pts))
        }
        return out
    }

    /** « 50 », « FR:urban » (50), « FR:rural » (80), « FR:motorway » (130), « FR:zone30 »… */
    fun parse(v: String?): Int? {
        if (v.isNullOrBlank()) return null
        val s = v.trim().lowercase()
        return when {
            s == "fr:urban" -> 50
            s == "fr:rural" -> 80
            s == "fr:motorway" -> 130
            s == "fr:walk" || s == "walk" -> 20
            else -> Regex("\\d+").find(s)?.value?.toIntOrNull()?.takeIf { it in 5..150 && !s.contains("mph") }
        }
    }
}
