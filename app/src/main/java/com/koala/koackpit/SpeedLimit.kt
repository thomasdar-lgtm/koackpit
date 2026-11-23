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
 * d'avance. Depuis 0.17.2, toutes les routes sont téléchargées (pas seulement celles avec panneau) : le panneau
 * suit la route où l'on est vraiment, et disparaît sur une route dont la limitation n'est pas connue (avant, le
 * dernier panneau restait affiché à tort : 110 ou 80 longtemps après avoir changé de route, constaté le 22/11).
 */
object SpeedLimit {
    val limit = mutableStateOf<Int?>(null)
    /** Limitation déduite (route sans panneau dans OpenStreetMap) : panneau affiché atténué. */
    val estimated = mutableStateOf(false)

    /**
     * Une route : limitation et tracé lat, lon, lat, lon… Depuis 0.17.7, chaque route a une limitation :
     * celle d'OpenStreetMap, sinon déduite selon les règles françaises (est = true) : 50 en ville (zone
     * d'habitation dans OpenStreetMap, ou rue résidentielle), 80 hors ville, 130 autoroute, 20 zone de rencontre.
     */
    private class Way(val limit: Int?, val pts: DoubleArray, val est: Boolean = false, val rev: Int? = null)
    // rev (HERE) : limitation dans le sens inverse du tracé, quand elle diffère (limit = sens du tracé)
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
        current = null; candidate = null; limit.value = null; estimated.value = false
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
        val hereWays = Here.ways(loc)
        if (hereWays != null) { show(loc, hereWays); return }   // HERE disponible pour cette zone
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
                    log("carreau ${if (missing == want[0]) "actuel" else "voisin"} ($missing) téléchargé : ${w.size} routes, ${w.count { !it.est }} avec limitation, ${w.count { it.est }} déduites")
                    lastError = null
                } catch (e: Exception) {
                    val m = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
                    if (m != lastError) log("téléchargement impossible ($m)")
                    lastError = m
                } finally { busy = false }
            }.start()
        }
        show(loc, want.mapNotNull { tiles[it] }.flatten())
    }

    private fun show(loc: Location, ways: List<Way>) {
        // Toutes les routes sont connues (pas seulement celles avec panneau) : on sait donc quand on quitte une route.
        // Changement de route validé sur 2 positions de suite (évite les sauts aux carrefours).
        val now = SystemClock.elapsedRealtime()
        val w = match(loc, ways)
        if (w != null) {
            lastMatch = now
            if (w === current) candidate = null
            else if (w === candidate) { current = w; candidate = null }
            else candidate = w
        } else if (now - lastMatch > 8_000) current = null   // plus aucune route connue sous la voiture
        val cw = current
        val shown = if (cw != null && !lastFwd && cw.rev != null) cw.rev else cw?.limit ?: cw?.rev
        val est = current?.est == true
        if (limit.value != shown || estimated.value != est) {
            limit.value = shown; estimated.value = est
            log(if (shown != null) "panneau affiché : $shown" + (if (est) " (déduit)" else "") + (if (Here.active) " [HERE]" else " [OSM]") else if (current != null) "route sans limitation connue : panneau retiré" else "hors route connue : panneau retiré")
        }
    }
    private var current: Way? = null
    private var candidate: Way? = null
    private var lastMatch = 0L

    // ---- Carreaux gardés sur l'autoradio ----
    // « limitations3 » (0.17.7) : limitations déduites en plus ; les anciens carreaux (parfois incomplets) sont effacés
    private fun file(k: String) = app?.let { ctx ->
        for (old in listOf("limitations", "limitations2")) java.io.File(ctx.filesDir, old).takeIf { it.exists() }?.deleteRecursively()
        java.io.File(java.io.File(ctx.filesDir, "limitations3").apply { mkdirs() }, k.replace(',', '_') + ".txt")
    }

    /** Format : une route par ligne « limite (suivie de e si déduite, ou -);lat,lon;lat,lon… ». */
    private fun saveCached(k: String, ws: List<Way>) = runCatching {
        file(k)?.writeText(ws.joinToString("\n") { w ->
            (w.limit?.let { "$it" + if (w.est) "e" else "" } ?: "-") + ";" + (0 until w.pts.size / 2).joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, w.pts[2 * it], w.pts[2 * it + 1]) }
        })
    }

    private fun loadCached(k: String): Boolean {
        val f = file(k) ?: return false
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) return false
        val ws = runCatching {
            f.readLines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split(';')
                val pts = parts.drop(1).flatMap { it.split(',').map(String::toDouble) }.toDoubleArray()
                Way(parts[0].trimEnd('e').toIntOrNull(), pts, parts[0].endsWith("e"))
            }
        }.getOrNull() ?: return false
        tiles[k] = ws
        return true
    }

    /** Route la plus proche (moins de 20 m), en écartant celles qui partent dans une autre direction. */
    private fun match(loc: Location, ws: List<Way>): Way? {
        if (ws.isEmpty()) return null
        val lat0 = loc.latitude; val lon0 = loc.longitude
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        var bestFwd = true
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
                if (score < 20 && moving && len2 > 4) {
                    val seg = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 180
                    val dir = (loc.bearing.toDouble() + 360) % 180
                    val diff = abs(seg - dir).let { if (it > 90) 180 - it else it }
                    if (diff > 35) score += 40   // route qui croise : seulement si rien de mieux
                }
                if (score < bestScore) {
                    bestScore = score; best = w
                    // Sens de marche par rapport au tracé (pour les routes à limitation différente selon le sens)
                    bestFwd = !moving || len2 <= 4 || run {
                        val segDir = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360
                        val d = abs(segDir - loc.bearing.toDouble()).let { if (it > 180) 360 - it else it }
                        d < 90
                    }
                }
                i += 2
            }
        }
        if (bestScore < 20) lastFwd = bestFwd
        return if (bestScore < 20) best else null
    }
    private var lastFwd = true

    private val SERVERS = listOf(
        // Ordre revu le 23/11 (essai depuis un serveur de GitHub) : kumi répond bien ; overpass-api.de refuse
        // souvent (réponse 406, connexion sécurisée refusée par l'autoradio)
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "http://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    @Volatile private var server = 0

    private fun post(url: String, q: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000; conn.readTimeout = 45_000
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
        val q = "[out:json][timeout:40];" +
            "way[highway~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
            "tertiary|tertiary_link|unclassified|residential|living_street|road)$\"]($bbox);out tags geom;" +
            // Zones d'habitation (= en ville, 50 par défaut) : pour déduire la limitation des routes sans panneau
            "way[landuse=residential]($bbox);out geom;"
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
        val js = JSONObject(txt)
        // Serveur surchargé : réponse coupée (« remark ») → carreau incomplet, on ne le garde pas
        js.optString("remark").takeIf { it.isNotBlank() }?.let { throw java.io.IOException("réponse incomplète du serveur : ${it.take(60)}") }
        val els = js.getJSONArray("elements")
        class Raw(val hw: String, val tags: JSONObject, val pts: DoubleArray)
        val roads = ArrayList<Raw>(); val towns = ArrayList<DoubleArray>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val tags = e.optJSONObject("tags") ?: continue
            val pts = DoubleArray(g.length() * 2)
            for (j in 0 until g.length()) { val o = g.optJSONObject(j) ?: continue; pts[2 * j] = o.optDouble("lat"); pts[2 * j + 1] = o.optDouble("lon") }
            if (tags.optString("landuse") == "residential") towns.add(pts)
            else roads.add(Raw(tags.optString("highway"), tags, pts))
        }
        val out = ArrayList<Way>(roads.size)
        for (r in roads) {
            val t = r.tags
            val known = parse(t.optString("maxspeed"))
                ?: parse(t.optString("maxspeed:forward"))
                ?: parse(t.optString("maxspeed:type"))
                ?: parse(t.optString("source:maxspeed"))
                ?: parse(t.optString("zone:maxspeed"))
                ?: when (r.hw) { "motorway", "motorway_link" -> 130; "living_street" -> 20; else -> null }   // sans ambiguïté
            if (known != null) { out.add(Way(known, r.pts)); continue }
            // Déduite : rue résidentielle ou route dans une zone d'habitation → 50, sinon 80
            val n = r.pts.size / 2
            val inTown = r.hw == "residential" || (n > 0 && towns.any { inside(it, r.pts[2 * (n / 2)], r.pts[2 * (n / 2) + 1]) })
            out.add(Way(if (inTown) 50 else 80, r.pts, est = true))
        }
        return out
    }

    /** Point dans un polygone (contour lat, lon…). */
    private fun inside(poly: DoubleArray, lat: Double, lon: Double): Boolean {
        var c = false; val n = poly.size / 2; var j = n - 1
        for (i in 0 until n) {
            val yi = poly[2 * i]; val xi = poly[2 * i + 1]; val yj = poly[2 * j]; val xj = poly[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
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

    /**
     * HERE (depuis 0.18.0) : limitations du fournisseur de cartes des GPS de voiture, bien plus complètes
     * qu'OpenStreetMap (où seule ~1 route sur 6 a sa limitation, constaté le 23/11 autour de Pontonx).
     * Service « Map Attributes » : carreaux par catégorie de route (niveau = catégorie + 8, de 9 grands axes à 13
     * petites rues). Pour la zone autour de la voiture : un seul appel avec tous les carreaux manquants, gardés
     * ensuite sur l'autoradio (30 jours). Forfait gratuit : 2 500 appels/mois ; Koackpit s'arrête à 2 000 et
     * repasse alors sur OpenStreetMap. Sans clé (BuildConfig.HERE_KEY vide) : OpenStreetMap seul.
     */
    private object Here {
        private const val MONTH_MAX = 2000
        private val tiles = java.util.concurrent.ConcurrentHashMap<String, List<Way>>()
        @Volatile private var busy = false
        @Volatile private var failAt = 0L
        @Volatile var active = false
        private var lastErr: String? = null
        private var columnsLogged = false

        private fun tileId(level: Int, lat: Double, lon: Double): Long {
            val size = 180.0 / (1 shl level)
            val y = ((lat + 90.0) / size).toLong(); val x = ((lon + 180.0) / size).toLong()
            return y * 2L * (1L shl level) + x
        }

        /** Carreaux utiles autour de la position : niveau 13 en 3×3, niveaux 9 à 12 qui les couvrent. */
        private fun needed(loc: Location): List<Pair<Int, Long>> {
            val s13 = 180.0 / (1 shl 13)
            val out = LinkedHashSet<Pair<Int, Long>>()
            for (dy in listOf(0, -1, 1)) for (dx in listOf(0, -1, 1)) {
                val la = loc.latitude + dy * s13; val lo = loc.longitude + dx * s13
                for (lv in 13 downTo 9) out.add(lv to tileId(lv, la, lo))
            }
            return out.toList()
        }

        private fun file(k: String) = app?.let { java.io.File(java.io.File(it.filesDir, "here").apply { mkdirs() }, "$k.txt") }

        /** Format : une route par ligne « limite sens tracé|limite sens inverse;lat,lon;… » (- = inconnue). */
        private fun save(k: String, ws: List<Way>) = runCatching {
            file(k)?.writeText(ws.joinToString("\n") { w ->
                (w.limit?.toString() ?: "-") + "|" + (w.rev?.toString() ?: "-") + ";" +
                    (0 until w.pts.size / 2).joinToString(";") { "%.5f,%.5f".format(java.util.Locale.US, w.pts[2 * it], w.pts[2 * it + 1]) }
            })
        }

        private fun load(k: String): Boolean {
            val f = file(k) ?: return false
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) return false
            val ws = runCatching {
                f.readLines().filter { it.isNotBlank() }.map { line ->
                    val parts = line.split(';'); val l = parts[0].split('|')
                    Way(l[0].toIntOrNull(), parts.drop(1).flatMap { it.split(',').map(String::toDouble) }.toDoubleArray(), rev = l.getOrNull(1)?.toIntOrNull())
                }
            }.getOrNull() ?: return false
            tiles[k] = ws
            return true
        }

        private fun monthCount(inc: Boolean): Int {
            val c = app ?: return 0
            val p = c.getSharedPreferences("here", Context.MODE_PRIVATE)
            val m = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
            var n = if (p.getString("month", "") == m) p.getInt("count", 0) else 0
            if (inc) { n++; p.edit().putString("month", m).putInt("count", n).apply() }
            return n
        }

        /** Routes HERE de la zone, ou null si HERE n'est pas (encore) disponible ici → OpenStreetMap. */
        fun ways(loc: Location): List<Way>? {
            if (BuildConfig.HERE_KEY.isBlank()) return null
            val need = needed(loc).map { (lv, id) -> "${lv}_$id" }
            val missing = need.filter { !tiles.containsKey(it) && !load(it) }
            if (missing.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                if (!busy && now - failAt > 30_000 && monthCount(false) < MONTH_MAX) {
                    busy = true
                    Thread {
                        try {
                            fetch(missing)
                            lastErr = null
                        } catch (e: Exception) {
                            failAt = SystemClock.elapsedRealtime()
                            val m = e.javaClass.simpleName + (e.message?.let { ": " + it.take(140) } ?: "")
                            if (m != lastErr) log("HERE : téléchargement impossible ($m) → OpenStreetMap en attendant")
                            lastErr = m
                        } finally { busy = false }
                    }.start()
                }
                // Le carreau de la petite rue où l'on est manque : pas encore de HERE ici
                if (missing.contains(need[0])) { active = false; return null }
            }
            active = true
            return need.mapNotNull { tiles[it] }.flatten()
        }

        private fun fetch(keys: List<String>) {
            val ids = keys.map { it.substringAfter('_') }
            val levels = keys.map { it.substringBefore('_').toInt() }.distinct().sorted()
            val layers = levels.flatMap { listOf("SPEED_LIMITS_FC${it - 8}", "ROAD_GEOM_FC${it - 8}") }
            val url = "https://smap.hereapi.com/v8/maps/attributes?layers=" + layers.joinToString(",") +
                "&in=tile:" + ids.joinToString(",") + "&apiKey=" + BuildConfig.HERE_KEY
            val n = monthCount(true)
            val conn = URL(url).openConnection() as HttpURLConnection
            val txt = try {
                conn.connectTimeout = 10_000; conn.readTimeout = 40_000
                conn.setRequestProperty("User-Agent", "Koackpit/${BuildConfig.VERSION_NAME}")
                if (conn.responseCode != 200) {
                    val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                    throw java.io.IOException("réponse ${conn.responseCode} ${body.take(120)}")
                }
                conn.inputStream.bufferedReader().readText()
            } finally { conn.disconnect() }
            val js = JSONObject(txt)
            val arr = js.optJSONArray("Tiles") ?: throw java.io.IOException("réponse inattendue : ${txt.take(120)}")
            // Par carreau : limitations (LINK_ID → sens tracé / sens inverse) et tracés (LINK_ID → points)
            val speeds = HashMap<String, HashMap<String, Pair<Int?, Int?>>>()
            val geoms = HashMap<String, HashMap<String, DoubleArray>>()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val meta = t.optJSONObject("Meta") ?: continue
                val layer = meta.optString("layerName"); val k = "${meta.optInt("level")}_${meta.optString("tileId")}"
                val rows = t.optJSONArray("Rows") ?: continue
                if (!columnsLogged && rows.length() > 0) {
                    columnsLogged = true
                    log("HERE : colonnes $layer = " + rows.getJSONObject(0).keys().asSequence().joinToString(","))
                }
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONObject(r); val link = row.optString("LINK_ID")
                    if (layer.startsWith("SPEED_LIMITS")) {
                        fun sp(c: String) = row.optString(c).toIntOrNull()?.takeIf { it in 5..150 }
                        val mph = row.optString("UNIT") == "M"
                        fun conv(v: Int?) = if (mph && v != null) (v * 1.609).toInt() else v
                        speeds.getOrPut(k) { HashMap() }[link] = conv(sp("FROM_REF_SPEED_LIMIT")) to conv(sp("TO_REF_SPEED_LIMIT"))
                    } else if (layer.startsWith("ROAD_GEOM")) {
                        val la = coords(row.optString("LAT")); val lo = coords(row.optString("LON"))
                        if (la.size >= 2 && la.size == lo.size) {
                            val pts = DoubleArray(la.size * 2); for (j in la.indices) { pts[2 * j] = la[j]; pts[2 * j + 1] = lo[j] }
                            geoms.getOrPut(k) { HashMap() }[link] = pts
                        }
                    }
                }
            }
            var withLimit = 0; var total = 0
            for (k in keys) {
                val g = geoms[k].orEmpty(); val sp = speeds[k].orEmpty()
                val ws = g.map { (link, pts) ->
                    val (from, to) = sp[link] ?: (null to null)
                    // Sens du tracé = depuis le nœud de référence (HERE) → FROM_REF ; sens inverse → TO_REF
                    Way(from ?: to, pts, rev = if (from != null && to != null && from != to) to else null)
                }
                withLimit += ws.count { it.limit != null }; total += ws.size
                tiles[k] = ws; save(k, ws)
            }
            log("HERE : ${keys.size} carreaux téléchargés, $total routes dont $withLimit avec limitation ($n appels ce mois-ci)")
        }

        /** « 4378123,12,-5,,3 » : 1er absolu, suivants en écart, vide = 0, en 1/100 000 de degré. */
        private fun coords(s: String): DoubleArray {
            if (s.isBlank()) return DoubleArray(0)
            val parts = s.split(','); val out = DoubleArray(parts.size); var v = 0L
            for (i in parts.indices) { v += parts[i].trim().toLongOrNull() ?: 0L; out[i] = v / 100_000.0 }
            return out
        }
    }
}
