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
 *
 * Constaté (journaux du 21/11) : la requête d'avant (toutes les routes à 1 km, à chaque fois) était trop lourde
 * pour ces serveurs gratuits souvent saturés → délais dépassés, erreurs 502, et donc aucun panneau.
 * Maintenant : la carte est découpée en carreaux d'environ 2 km ; pour chaque carreau on ne télécharge QUE les
 * routes qui ont une limitation (petite réponse), une seule fois : gardé ensuite sur l'autoradio (30 jours),
 * donc les trajets habituels n'ont plus besoin du réseau. Le carreau où l'on est et ses voisins sont chargés
 * d'avance. Le dernier panneau reconnu reste affiché jusqu'au suivant (ou l'arrêt de la voiture).
 */
object SpeedLimit {
    val limit = mutableStateOf<Int?>(null)

    private class Way(val limit: Int, val pts: DoubleArray)   // lat, lon, lat, lon…
    private const val TILE = 0.02                              // degrés (≈ 2,2 km × 1,6 km ici)
    private val tiles = java.util.concurrent.ConcurrentHashMap<String, List<Way>>()
    @Volatile private var busy = false
    private var lastTry = HashMap<String, Long>()
    private var lm: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onLocation(loc)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private var app: Context? = null
    private var lastError: String? = null
    private var src: String? = null

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (lm != null) return
        app = ctx.applicationContext
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val r = runCatching { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()); lm = m }
        WakeLog.add(ctx, "Limitations : GPS de l'autoradio " + (r.exceptionOrNull()?.let { "indisponible (${it.javaClass.simpleName})" } ?: "suivi"))
    }

    private fun log(msg: String) { app?.let { WakeLog.add(it, "Limitations : $msg") } }

    fun stop() {
        runCatching { lm?.removeUpdates(listener) }
        lm = null
        held = null; limit.value = null
    }

    /** Position du téléphone (plus précise) : utilisée en priorité quand elle arrive. */
    fun onPhoneLocation(loc: Location) { phoneAt = SystemClock.elapsedRealtime(); source("téléphone"); handle(loc) }
    private fun source(s: String) { if (src != s) { src = s; log("position venant du $s") } }
    private var phoneAt = 0L

    private fun onLocation(loc: Location) {
        // GPS de l'autoradio seulement si le téléphone ne donne pas sa position
        if (SystemClock.elapsedRealtime() - phoneAt < 4000) return
        source("GPS de l'autoradio")
        handle(loc)
    }

    private fun key(ty: Int, tx: Int) = "$ty,$tx"

    private fun handle(loc: Location) {
        val ty = Math.floor(loc.latitude / TILE).toInt(); val tx = Math.floor(loc.longitude / TILE).toInt()
        // Carreau actuel d'abord, puis ses voisins
        val want = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, -1 to 1, 1 to -1, 1 to 1).map { (a, b) -> key(ty + a, tx + b) }
        val missing = want.firstOrNull { !tiles.containsKey(it) && !loadCached(it) }
        if (missing != null && !busy && SystemClock.elapsedRealtime() - (lastTry[missing] ?: 0L) > 20_000) {
            busy = true; lastTry[missing] = SystemClock.elapsedRealtime()
            Thread {
                try {
                    val w = fetchTile(missing)
                    tiles[missing] = w; saveCached(missing, w)
                    log("carreau ${if (missing == want[0]) "actuel" else "voisin"} téléchargé : ${w.size} routes avec limitation")
                    lastError = null
                } catch (e: Exception) {
                    val m = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
                    if (m != lastError) log("téléchargement impossible ($m)")
                    lastError = m
                } finally { busy = false }
            }.start()
        }
        val m = match(loc, want.mapNotNull { tiles[it] }.flatten())
        if (m != null) held = m
        if (limit.value != held) { limit.value = held; if (held != null) log("panneau affiché : $held") }
    }
    /** Dernier panneau reconnu : reste affiché jusqu'au suivant (remis à zéro à l'arrêt de la voiture). */
    private var held: Int? = null

    // ---- Carreaux gardés sur l'autoradio ----
    private fun file(k: String) = app?.let { java.io.File(java.io.File(it.filesDir, "limitations").apply { mkdirs() }, k.replace(',', '_') + ".txt") }

    /** Format : une route par ligne « limite;lat,lon;lat,lon… ». */
    private fun saveCached(k: String, ws: List<Way>) = runCatching {
        file(k)?.writeText(ws.joinToString("\n") { w ->
            w.limit.toString() + ";" + (0 until w.pts.size / 2).joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, w.pts[2 * it], w.pts[2 * it + 1]) }
        })
    }

    private fun loadCached(k: String): Boolean {
        val f = file(k) ?: return false
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) return false
        val ws = runCatching {
            f.readLines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(';')
                val pts = parts.drop(1).flatMap { it.split(',').map(String::toDouble) }.toDoubleArray()
                Way(parts[0].toInt(), pts)
            }
        }.getOrNull() ?: return false
        tiles[k] = ws
        return true
    }

    /** Route la plus proche (moins de 25 m), en écartant celles qui partent dans une autre direction. */
    private fun match(loc: Location, ws: List<Way>): Int? {
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

    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "http://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    @Volatile private var server = 0

    private fun post(url: String, q: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000; conn.readTimeout = 30_000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Koackpit/${BuildConfig.VERSION_NAME}")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) throw java.io.IOException("réponse ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    /** Routes avec limitation d'un carreau (et autoroutes : 130 par défaut en France). */
    private fun fetchTile(k: String): List<Way> {
        val (ty, tx) = k.split(',').map { it.toInt() }
        val s0 = ty * TILE; val w0 = tx * TILE
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, s0, w0, s0 + TILE, w0 + TILE)
        val q = "[out:json][timeout:25];(" +
            "way[highway][maxspeed]($bbox);" +
            "way[highway][\"maxspeed:type\"]($bbox);" +
            "way[highway][\"source:maxspeed\"]($bbox);" +
            "way[highway][\"zone:maxspeed\"]($bbox);" +
            "way[highway=motorway]($bbox););out tags geom;"
        // Le serveur principal refuse parfois la connexion sécurisée de l'autoradio : on essaie les adresses
        // dans l'ordre et on garde la première qui répond
        var txt: String? = null
        var err: Exception? = null
        for (n in SERVERS.indices) {
            val url = SERVERS[(server + n) % SERVERS.size]
            try {
                txt = post(url, q)
                if ((server + n) % SERVERS.size != server) { server = (server + n) % SERVERS.size; log("serveur utilisé : $url") }
                break
            } catch (e: Exception) { err = e }
        }
        if (txt == null) throw err ?: java.io.IOException("aucun serveur")
        val els = JSONObject(txt).getJSONArray("elements")
        val out = ArrayList<Way>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val lim = parse(tags.optString("maxspeed"))
                ?: parse(tags.optString("maxspeed:type"))
                ?: parse(tags.optString("source:maxspeed"))
                ?: parse(tags.optString("zone:maxspeed"))
                ?: if (tags.optString("highway") == "motorway") 130 else null
            if (lim == null) continue
            val pts = DoubleArray(g.length() * 2)
            for (j in 0 until g.length()) { val o = g.getJSONObject(j); pts[2 * j] = o.getDouble("lat"); pts[2 * j + 1] = o.getDouble("lon") }
            out.add(Way(lim, pts))
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
            s == "fr:zone30" -> 30
            s == "fr:zone20" -> 20
            else -> Regex("\\d+").find(s)?.value?.toIntOrNull()?.takeIf { it in 5..150 && !s.contains("mph") }
        }
    }
}
