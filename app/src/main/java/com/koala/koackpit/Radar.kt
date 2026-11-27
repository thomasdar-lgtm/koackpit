package com.koala.koackpit

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
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
        spots = l; cur = null; done.clear()
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
        // Rue du prochain radar demandée à l'avance (jusqu'à 4 km) : la réponse de HERE peut prendre
        // plusieurs secondes ; demandée seulement à l'entrée de la zone, l'alerte arrivait radar passé (27/11).
        ahead?.let { if (it !== best) sameRoad(ctx, it) }
        val s = best ?: return

        // Même route ? (rue du radar selon HERE, demandée une fois en arrière-plan)
        when (val v = sameRoad(ctx, s)) {
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

    // Rue de chaque radar (HERE), gardée d'un trajet à l'autre ; « ? » = HERE n'a pas pu répondre
    private val roads = HashMap<Int, String>()

    private fun known(ctx: Context, s: Spot): Boolean = roads.containsKey(s.n) ||
        ctx.getSharedPreferences("radar-rues", Context.MODE_PRIVATE).contains("r${s.n}")
    @Volatile private var asking = -1
    private var asked = 0L

    /** true = même rue (ou impossible à savoir), false = autre rue, null = réponse en attente. */
    private fun sameRoad(ctx: Context, s: Spot): Boolean? {
        val p = ctx.getSharedPreferences("radar-rues", Context.MODE_PRIVATE)
        val road = roads[s.n] ?: p.getString("r${s.n}", null)?.also { roads[s.n] = it }
        if (road == null) {
            if (BuildConfig.HERE_KEY.isBlank()) return true      // pas de HERE (essais) : comme avant
            val now = SystemClock.elapsedRealtime()
            if (asking == s.n && now - asked < 15_000) return null
            if (asking == s.n) { roads[s.n] = "?"; return true }        // pas de réponse en 15 s : on alerte
            asking = s.n; asked = now
            Thread {
                val r = SpeedLimit.streetAt(s.lat, s.lon)
                roads[s.n] = r ?: "?"
                if (r != null) p.edit().putString("r${s.n}", r).apply()
                asking = -1
            }.start()
            return null
        }
        val here = SpeedLimit.currentStreet()
        if (road == "?" || here == null) return true
        fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]"), "")
        val a = norm(road); val b = norm(here)
        val same = a == b || (a.length > 3 && b.length > 3 && (a.contains(b) || b.contains(a)))
        if (!same) log("radar n°${s.n} ignoré : sur « $road », on roule sur « $here »")
        return same
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
