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
            connectTimeout = 800; readTimeout = 1500; useCaches = false
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
                    main.post {
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
