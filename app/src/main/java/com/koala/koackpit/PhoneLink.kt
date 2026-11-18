package com.koala.koackpit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Liaison avec l'appli « Koackpit Téléphone » du S25.
 * L'autoradio est connecté au partage de connexion du téléphone : le téléphone est donc la « passerelle »
 * du Wi-Fi. Koackpit y interroge le petit serveur de l'appli (port 47800) : morceau en cours + pochette.
 */
object PhoneLink {
    private const val PORT = 47800

    val connected = mutableStateOf(false)
    val title = mutableStateOf("")
    val artist = mutableStateOf("")
    val playing = mutableStateOf(false)
    val art = mutableStateOf<ImageBitmap?>(null)
    /** Position dans le morceau (ms) reçue du téléphone, l'heure de réception, et la durée (ms). */
    @Volatile private var pos = 0L
    @Volatile private var posAt = 0L
    val duration = mutableStateOf(0L)
    /** Appli qui joue sur le téléphone (Deezer, YouTube, TikTok…). */
    val appName = mutableStateOf("")
    /** Batterie du téléphone : pourcentage et en charge ou non (null si inconnue). */
    val battery = mutableStateOf<Pair<Int, Boolean>?>(null)
    /** Position GPS du téléphone, plus précise que celle de l'autoradio, et l'heure locale de sa mesure. */
    @Volatile var phoneLoc: android.location.Location? = null
    @Volatile var phoneLocAt = 0L
    /** Dernière notification déjà récupérée (-1 : pas encore de contact, on ne réaffiche pas l'historique). */
    @Volatile private var notifSeq = -1

    fun phoneLocation(): android.location.Location? =
        phoneLoc?.takeIf { android.os.SystemClock.elapsedRealtime() - phoneLocAt < 5000 }

    private fun friendly(pkg: String) = when {
        pkg.contains("deezer") -> "Deezer"; pkg.contains("spotify") -> "Spotify"
        pkg.contains("youtube.music") || pkg.contains("apps.youtube.music") -> "YouTube Music"
        pkg.contains("youtube") -> "YouTube"; pkg.contains("musically") || pkg.contains("trill") -> "TikTok"
        pkg.contains("soundcloud") -> "SoundCloud"; pkg.contains("radiofrance") -> "Radio France"
        pkg.contains("podcast") -> "Podcasts"; pkg.isEmpty() -> ""
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Commande du lecteur du téléphone : toggle, play, pause, next, prev, seek (ms). */
    fun command(a: String, ms: Long = 0) {
        val gw = gateway() ?: return
        Thread {
            get("http://$gw:$PORT/cmd?a=$a&ms=$ms")
            app?.let { WakeLog.add(it, "Commande lecture envoyée au téléphone : $a" + if (a == "seek") " ${ms / 1000} s" else "") }
        }.start()
        // Pas de position supposée : la barre suit ce que le téléphone renvoie (si Deezer refuse, elle ne bouge pas)
    }

    private var artId = ""
    private var app: Context? = null
    private var started = false
    private var loggedConnect = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        Thread({ loop() }, "PhoneLink").start()
    }

    /** Adresse du téléphone = passerelle du Wi-Fi (partage de connexion). */
    @Suppress("DEPRECATION")
    private fun gateway(): String? {
        val wm = app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val g = runCatching { wm.dhcpInfo.gateway }.getOrDefault(0)
        if (g == 0) return null
        return "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
    }

    private fun get(url: String): ByteArray? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 2000; readTimeout = 3000; useCaches = false
            try { if (responseCode == 200) inputStream.readBytes() else null } finally { disconnect() }
        }
    }.getOrNull()

    private fun loop() {
        val main = Handler(Looper.getMainLooper())
        while (true) {
            val awake = (app?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
            var ok = false
            if (awake) {
                val gw = gateway()
                val body = gw?.let { get("http://$it:$PORT/state") }
                val js = body?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
                if (js != null) {
                    ok = true
                    val newArt = js.optString("art")
                    val bmp = if (newArt.isNotEmpty() && newArt != artId) get("http://$gw:$PORT/art")
                        ?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() } else null
                    val p = js.optLong("pos", -1L)
                    if (p >= 0) { pos = p; posAt = android.os.SystemClock.elapsedRealtime() }
                    // Position GPS du téléphone (si autorisée sur le téléphone)
                    js.optJSONObject("loc")?.let { l ->
                        val age = l.optLong("age", 99_999)
                        if (age < 5000) {
                            val loc = android.location.Location("telephone").apply {
                                latitude = l.optDouble("lat"); longitude = l.optDouble("lon"); accuracy = l.optDouble("acc", 10.0).toFloat()
                                l.optDouble("speed", -1.0).takeIf { it >= 0 }?.let { speed = it.toFloat() }
                                l.optDouble("bearing", -1.0).takeIf { it >= 0 }?.let { bearing = it.toFloat() }
                            }
                            phoneLoc = loc; phoneLocAt = android.os.SystemClock.elapsedRealtime() - age
                            main.post { SpeedLimit.onPhoneLocation(loc) }
                        }
                    }
                    // Nouveaux messages / appels manqués
                    val seq = js.optInt("notif", 0)
                    if (notifSeq < 0) notifSeq = seq
                    else if (seq > notifSeq) {
                        val arr = get("http://$gw:$PORT/notifs?since=$notifSeq")?.let { runCatching { org.json.JSONArray(String(it)) }.getOrNull() }
                        if (arr != null) {
                            notifSeq = seq
                            val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                                NotifOverlay.Notif(it.optString("appName"), it.optString("title"), it.optString("text"), it.optString("kind") == "call")
                            }
                            app?.let { a -> main.post { NotifOverlay.show(a, list) } }
                        }
                    }
                    main.post {
                        appName.value = friendly(js.optString("app"))
                        val b = js.optInt("batt", -1)
                        battery.value = if (b >= 0) b to js.optBoolean("charging") else null
                        duration.value = js.optLong("dur", 0L)
                        connected.value = true
                        title.value = js.optString("title"); artist.value = js.optString("artist")
                        playing.value = js.optBoolean("playing")
                        if (newArt.isEmpty()) { art.value = null; artId = "" }
                        else if (bmp != null) { art.value = bmp; artId = newArt }
                    }
                    if (!loggedConnect) { loggedConnect = true; app?.let { WakeLog.add(it, "Téléphone compagnon connecté ($gw)") } }
                }
            }
            if (ok) lastOk = android.os.SystemClock.elapsedRealtime()
            // Coupure du Wi-Fi : on garde les dernières infos 3 min (la position continue d'avancer sur place)
            else if (android.os.SystemClock.elapsedRealtime() - lastOk > 180_000) main.post { connected.value = false }
            // 1,5 s quand le téléphone répond ; sinon on retente toutes les 2 s pour reprendre vite
            Thread.sleep(if (ok) 1500 else 2000)
        }
    }

    /**
     * Position actuelle du morceau en ms, d'après le téléphone (exacte, même après une avance ou une reprise
     * en milieu de titre). null si l'appli du téléphone ne répond pas ou ne donne pas la position.
     */
    @Volatile private var lastOk = 0L

    /**
     * [btPlaying] : lecture/pause d'après le Bluetooth. Pendant une coupure du Wi-Fi, c'est lui qui dit si la
     * position doit continuer d'avancer (le téléphone ne répond plus) : on repart de la position estimée.
     */
    fun positionMs(btPlaying: Boolean): Long? {
        if (!connected.value || posAt == 0L) return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOk > 4000 && btPlaying != playing.value) {
            pos = if (playing.value) pos + (now - posAt) else pos
            posAt = now
            playing.value = btPlaying
        }
        val p = if (playing.value) pos + (now - posAt) else pos
        val d = duration.value
        return if (d > 0) p.coerceIn(0, d) else p
    }

    /** Le téléphone joue-t-il ce titre (celui annoncé par le Bluetooth) ? */
    fun matches(btTitle: String): Boolean {
        if (!connected.value) return false
        val p = title.value.trim().lowercase(); val b = btTitle.trim().lowercase()
        return b.isEmpty() || p == b || p.contains(b) || b.contains(p)
    }

    /** Pochette à afficher pour ce titre (null si le téléphone ne joue pas ce morceau). */
    fun artFor(btTitle: String): ImageBitmap? {
        val a = art.value ?: return null
        if (!connected.value) return null
        val p = title.value.trim().lowercase(); val b = btTitle.trim().lowercase()
        return if (b.isEmpty() || p == b || p.contains(b) || b.contains(p)) a else null
    }
}
