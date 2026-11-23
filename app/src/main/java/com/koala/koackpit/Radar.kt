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
 */
object Radar {
    /** Zone en cours : type (libellé), vitesse contrôlée (null si sans objet), mètres restants, excès de vitesse. */
    data class Alert(val kind: String, val vma: Int?, val leftM: Int, val over: Boolean)
    val alert = mutableStateOf<Alert?>(null)

    private class Spot(val type: String, val vma: Int?, val lat: Double, val lon: Double) {
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
            else -> "Contrôle de vitesse"
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
                val l = ctx.assets.open("radars.txt").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split(';')
                    if (p.size < 4) null else Spot(p[0], p[1].toIntOrNull(), p[2].toDouble(), p[3].toDouble())
                }
                spots = l
                log("${l.size} radars fixes chargés (liste officielle)")
            } catch (e: Exception) {
                log("liste illisible (${e.javaClass.simpleName})")
            } finally { loading = false }
        }.start()
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
                alert.value = Alert(c.label, c.vma, left.toInt(), c.vma != null && kmh != null && kmh > c.vma)
                return
            }
        }
        // Nouvelle zone : radar devant soi, à moins de « before » mètres
        val moving = loc.hasBearing() && kmh != null && kmh >= 15
        if (!moving) return
        done.entries.removeAll { now - it.value > 10 * 60_000L }
        val kx = 111_320.0 * cos(Math.toRadians(loc.latitude)); val ky = 110_540.0
        var best: Spot? = null; var bestD = Double.MAX_VALUE
        for (s in list) {
            val dy = (s.lat - loc.latitude) * ky
            if (abs(dy) > 2100) continue
            val dx = (s.lon - loc.longitude) * kx
            if (abs(dx) > 2100) continue
            val dd = Math.sqrt(dx * dx + dy * dy)
            if (dd > s.before || dd >= bestD || done.containsKey(s)) continue
            val ang = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            val diff = abs(ang - loc.bearing).let { if (it > 180) 360 - it else it }
            if (diff > 30) continue
            best = s; bestD = dd
        }
        val s = best ?: return
        cur = s; done[s] = now
        walked = s.before - bestD   // on entre dans la zone là où on la détecte
        lastLat = loc.latitude; lastLon = loc.longitude
        val left = (s.len - walked).toInt()
        alert.value = Alert(s.label, s.vma, left, s.vma != null && kmh != null && kmh > s.vma)
        log("zone de danger : ${s.label}${s.vma?.let { " $it" } ?: ""}, ${s.len.toInt()} m")
        beep()
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
