package com.koala.koackpit

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Alertes de zones de danger (0.19.0), d'après la liste officielle des radars fixes (data.gouv.fr, assets/radars.txt).
 *
 * Conforme au Code de la route (R413-15, décret 2012-3) : Koackpit n'indique jamais l'emplacement d'un radar, seulement
 * une zone de danger d'au moins 4 km sur autoroute (limitation ≥ 110), 2 km hors ville, 300 m en ville. Le radar
 * se trouve quelque part dans la zone (position dans la zone propre à chaque radar, entre 20 et 80 %), donc ni le début
 * ni la fin de la zone ne permettent de le situer. Le bandeau affiche la distance jusqu'à la FIN de la zone.
 *
 * Le fichier ne donne pas le sens contrôlé : une zone peut s'afficher dans les deux sens de circulation.
 *
 * Même route (0.20.7) : un radar « devant » à vol d'oiseau peut être sur une autre route (parallèle, croisement,
 * voie voisine) — fausse alerte du 27/11. Avant d'annoncer, Koackpit demande à HERE la rue du radar (une fois par
 * radar, gardée en mémoire) et la compare à la rue où l'on roule. Rues différentes → pas d'alerte. Sans réponse
 * de HERE (pas d'Internet, quota du jour atteint) : alerte comme avant, pour ne jamais rater un vrai radar.
 */
object Radar {
    /** Zone en cours : type (libellé), vitesse contrôlée (null si sans objet), mètres restants, excès de vitesse. */
    data class Alert(val kind: String, val vma: Int?, val leftM: Int, val over: Boolean)
    val alert = mutableStateOf<Alert?>(null)

    private class Spot(val n: Int, val type: String, val vma: Int?, val lat: Double, val lon: Double) {
        /** Longueur de la zone de danger (minimums légaux). */
        val len: Double = when {
            type == "ETFR" || type == "ETU" -> 300.0
            type == "ETPN" -> 2000.0
            vma == null || vma <= 50 -> 300.0
            vma >= 110 -> 4000.0
            else -> 2000.0
        }
        /** Part de la zone avant le radar (20–80 %, fixe pour un radar donné). */
        val before: Double = len * (0.2 + 0.6 * (((lat * 1e5).toLong() * 31 + (lon * 1e5).toLong()).mod(1000L)) / 1000.0)
        val label get() = when (type) {
            "ETVM" -> "Vitesse moyenne"
            "ETFR" -> "Feu rouge"
            "ETPN" -> "Passage à niveau"
            else -> "Vitesse"
        }
    }

    @Volatile private var spots: List<Spot>? = null
    @Volatile private var loading = false
    private var app: Context? = null

    // Zone en cours
    private var cur: Spot? = null
    private var walked = 0.0
    private var lastLat = 0.0; private var lastLon = 0.0
    private val done = HashMap<Spot, Long>()   // zones déjà annoncées (pas deux fois de suite)

    private fun log(msg: String) { app?.let { WakeLog.add(it, "Radars : $msg") } }

    private fun load(ctx: Context) {
        if (spots != null || loading) return
        loading = true; app = ctx.applicationContext
        Thread {
            try {
                val n = loadFrom(ctx.assets.open("radars.txt").bufferedReader().readLines())
                log("$n radars fixes chargés (liste officielle)")
            } catch (e: Exception) {
                log("liste illisible (${e.javaClass.simpleName})")
            } finally { loading = false }
        }.start()
    }

    /** Lit la liste « type;vitesse;lat;lon » (aussi utilisé par l'essai automatique). */
    internal fun loadFrom(lines: List<String>): Int {
        val l = lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexedNotNull { i, line ->
            val p = line.split(';')
            if (p.size < 4) null else Spot(i, p[0], p[1].toIntOrNull(), p[2].toDouble(), p[3].toDouble())
        }
        spots = l; cur = null; done.clear(); near.clear()
        return l.size
    }

    /** Appelé à chaque position (téléphone ou GPS de l'autoradio). */
    fun tick(ctx: Context, loc: Location) {
        val list = spots ?: run { load(ctx); return }
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else null
        val now = SystemClock.elapsedRealtime()
        val c = cur
        if (c != null) {
            val d = FloatArray(1)
            Location.distanceBetween(lastLat, lastLon, loc.latitude, loc.longitude, d)
            if (d[0] < 500f) walked += d[0]          // saut de position (GPS perdu) : ignoré
            lastLat = loc.latitude; lastLon = loc.longitude
            val left = c.len - walked
            val toSpot = dist(loc, c)
            // Fin de zone : longueur parcourue, ou route quittée (on s'éloigne au lieu d'avancer dans la zone)
            if (left <= 0 || toSpot > c.len + 300) {
                log("fin de zone (${c.label})")
                cur = null; alert.value = null
            } else {
                val v = shownVma(c)
                alert.value = Alert(c.label, v, left.toInt(), v != null && kmh != null && kmh > v)
                return
            }
        }
        // Nouvelle zone : radar devant soi, à moins de « before » mètres
        val moving = loc.hasBearing() && kmh != null && kmh >= 15
        if (!moving) return
        done.entries.removeAll { now - it.value > 10 * 60_000L }
        val kx = 111_320.0 * cos(Math.toRadians(loc.latitude)); val ky = 110_540.0
        var best: Spot? = null; var bestD = Double.MAX_VALUE
        var ahead: Spot? = null; var aheadD = Double.MAX_VALUE
        for (s in list) {
            val dy = (s.lat - loc.latitude) * ky
            if (abs(dy) > PREFETCH) continue
            val dx = (s.lon - loc.longitude) * kx
            if (abs(dx) > PREFETCH) continue
            val dd = Math.sqrt(dx * dx + dy * dy)
            if (dd > PREFETCH || done.containsKey(s)) continue
            val ang = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            val diff = abs(ang - loc.bearing).let { if (it > 180) 360 - it else it }
            if (diff > 30) continue
            if (dd < aheadD && !known(ctx, s)) { ahead = s; aheadD = dd }
            if (dd > s.before || dd >= bestD) continue
            best = s; bestD = dd
        }
        // Routes du prochain radar demandées à l'avance (jusqu'à 4 km) : la réponse peut prendre plusieurs
        // secondes ; demandée seulement à l'entrée de la zone, l'alerte arrivait radar passé (27/11).
        ahead?.let { if (it !== best) around(ctx, it) }
        val s = best ?: return

        // Même route et même sens ? (routes autour du radar, demandées une fois en arrière-plan)
        when (val v = sameRoad(ctx, s, loc)) {
            null -> return                                     // réponse en attente : on revérifie à la position suivante
            false -> { done[s] = now; return }                 // autre route : ignoré 10 min
            true -> {}
        }
        cur = s; done[s] = now
        walked = s.before - bestD   // on entre dans la zone là où on la détecte
        lastLat = loc.latitude; lastLon = loc.longitude
        val left = (s.len - walked).toInt()
        val v = shownVma(s)
        alert.value = Alert(s.label, v, left, v != null && kmh != null && kmh > v)
        log("zone de danger : ${s.label}${s.vma?.let { " $it (liste officielle)" } ?: ""}, affiché ${v ?: "sans vitesse"}, ${s.len.toInt()} m")
        beep()
    }

    private const val PREFETCH = 4000.0

    /**
     * Vitesse affichée dans le bandeau : celle de la route (panneau affiché par Koackpit), pas celle de la
     * liste officielle, qui peut être fausse ou ancienne (radar noté 80 sur une route à 110, 27/11).
     * Feu rouge / passage à niveau : pas de vitesse.
     */
    private fun shownVma(s: Spot): Int? =
        if (s.type == "ETFR" || s.type == "ETPN") null else SpeedLimit.limit.value ?: s.vma

    // Routes autour de chaque radar, gardées d'un trajet à l'autre ; « ? » = pas de réponse (ce trajet seulement)
    private val near = HashMap<Int, String>()

    private fun known(ctx: Context, s: Spot): Boolean = near.containsKey(s.n) ||
        ctx.getSharedPreferences("radar-routes", Context.MODE_PRIVATE).contains("o${s.n}")
    @Volatile private var asking = -1
    private var asked = 0L

    /**
     * Routes réelles autour du radar (OpenStreetMap, 35 m) : nom/numéro, sens unique ou non, cap de la chaussée
     * au droit du radar, distance. Remplace la rue donnée par HERE, qui se trompait sur les 2×2 voies longées
     * d'une petite route (27/11, D824 : radar placé sur « Route de Peyrehitte », donc ignoré, puis alerte pour
     * le radar de la chaussée d'en face). Gardé d'un trajet à l'autre.
     * Format : une route par ligne « noms|sensunique(0/1)|cap|distance ».
     */
    /** Essais sans réseau : routes fournies par le test (n° de radar → lignes « noms|sensunique|cap|distance »). */
    internal var testRoads: Map<Int, String>? = null

    private fun around(ctx: Context, s: Spot): List<Road>? {
        testRoads?.let { t -> return t[s.n]?.lines()?.mapNotNull(Road::parse) ?: emptyList() }
        val p = ctx.getSharedPreferences("radar-routes", Context.MODE_PRIVATE)
        val raw = near[s.n] ?: p.getString("o${s.n}", null)?.also { near[s.n] = it }
        if (raw != null) return if (raw == "?") emptyList() else raw.lines().mapNotNull(Road::parse)
        val now = SystemClock.elapsedRealtime()
        if (asking == s.n && now - asked < 25_000) return null
        if (asking == s.n) { near[s.n] = "?"; log("radar n°${s.n} : routes inconnues (pas de réponse)"); return emptyList() }
        if (asking != -1 && now - asked < 25_000) return null   // une demande à la fois
        asking = s.n; asked = now
        Thread {
            val r = runCatching { fetchRoads(s) }.getOrNull()
            if (r != null) {
                val txt = r.joinToString("\n") { it.line() }
                near[s.n] = txt
                p.edit().putString("o${s.n}", txt).apply()
            } else near[s.n] = "?"
            asking = -1
        }.start()
        return null
    }

    private class Road(val names: String, val oneway: Boolean, val cap: Int, val d: Int) {
        fun line() = "$names|${if (oneway) 1 else 0}|$cap|$d"
        companion object {
            fun parse(l: String): Road? = l.split('|').takeIf { it.size == 4 }?.let {
                Road(it[0], it[1] == "1", it[2].toIntOrNull() ?: return null, it[3].toIntOrNull() ?: return null)
            }
        }
    }

    private const val HW = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
        "tertiary|tertiary_link|unclassified|residential|living_street|road"

    private fun fetchRoads(s: Spot): List<Road>? {
        val q = "[out:json][timeout:20];way(around:35,%.6f,%.6f)[highway~\"^($HW)$\"];out tags geom;"
            .format(java.util.Locale.US, s.lat, s.lon)
        val js = JSONObject(SpeedLimit.overpass(q) ?: return null)
        val els = js.getJSONArray("elements")
        val kx = 111_320.0 * cos(Math.toRadians(s.lat)); val ky = 110_540.0
        val out = ArrayList<Road>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val t = e.optJSONObject("tags") ?: JSONObject()
            val g = e.optJSONArray("geometry") ?: continue
            var bestD = Double.MAX_VALUE; var bestCap = 0.0
            for (k in 0 until g.length() - 1) {
                val a = g.getJSONObject(k); val b = g.getJSONObject(k + 1)
                val ax = (a.getDouble("lon") - s.lon) * kx; val ay = (a.getDouble("lat") - s.lat) * ky
                val bx = (b.getDouble("lon") - s.lon) * kx; val by = (b.getDouble("lat") - s.lat) * ky
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val u = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(ax + u * dx, ay + u * dy)
                if (d < bestD) { bestD = d; bestCap = (Math.toDegrees(atan2(dx, dy)) + 360) % 360 }
            }
            if (bestD == Double.MAX_VALUE) continue
            val hw = t.optString("highway"); val ow = t.optString("oneway")
            val oneway = ow in setOf("yes", "1", "true", "-1") ||
                (ow != "no" && (hw == "motorway" || t.optString("junction") in setOf("roundabout", "circular")))
            if (ow == "-1") bestCap = (bestCap + 180) % 360
            val names = listOf(t.optString("ref"), t.optString("name")).flatMap { it.split(';') }
                .map(::norm).filter { it.isNotBlank() }.joinToString(",")
            out += Road(names, oneway, bestCap.roundToInt(), bestD.roundToInt())
        }
        return out
    }

    private fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun angle(a: Float, b: Int): Float = abs(a - b).let { if (it > 180) 360 - it else it }

    /**
     * Le radar contrôle-t-il la route et le sens où l'on roule ? null = en attente des routes.
     * - route connue (HERE) : on garde les chaussées du radar qui portent ce nom ou ce numéro ;
     *   aucune → radar sur une autre route (ignoré) ;
     * - 2×2 voies (chaussées à sens unique) : la chaussée de notre sens doit être la plus proche du radar,
     *   sinon il est sur la chaussée d'en face (ignoré) ;
     * - sinon (routes inconnues, pas de réponse) : on alerte, comme avant.
     */
    private fun sameRoad(ctx: Context, s: Spot, loc: Location): Boolean? {
        val list = around(ctx, s) ?: return null
        if (list.isEmpty()) return true
        val here = SpeedLimit.currentStreet()?.let(::norm)?.takeIf { it.isNotBlank() }
        fun match(r: Road) = here == null || r.names.split(',').any { n ->
            n == here || (n.length > 3 && here.length > 3 && (n.contains(here) || here.contains(n)))
        }
        val ours = list.filter(::match)
        if (ours.isEmpty()) {
            log("radar n°${s.n} ignoré : routes du radar « ${list.joinToString(" / ") { it.names.ifBlank { "?" } }} », on roule sur « ${SpeedLimit.currentStreet()} »")
            return false
        }
        if (!loc.hasBearing()) return true
        val ways = ours.filter { it.oneway }
        if (ways.isEmpty()) return true
        val mine = ways.filter { angle(loc.bearing, it.cap) < 70 }.minByOrNull { it.d }
        val other = ways.filter { angle(loc.bearing, it.cap) > 110 }.minByOrNull { it.d }
        if (other != null && (mine == null || other.d + 3 < mine.d)) {
            log("radar n°${s.n} ignoré : sur la chaussée d'en face (${other.d} m contre ${mine?.d ?: "–"} m)")
            return false
        }
        return true
    }

    private fun dist(loc: Location, s: Spot): Float {
        val d = FloatArray(1); Location.distanceBetween(loc.latitude, loc.longitude, s.lat, s.lon, d); return d[0]
    }

    /** Deux bips courts au début de la zone. */
    private fun beep() {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { t.release() } }, 800)
        }.onFailure { log("bip impossible (${it.javaClass.simpleName})") }
    }

    fun stop() { cur = null; alert.value = null }
}
