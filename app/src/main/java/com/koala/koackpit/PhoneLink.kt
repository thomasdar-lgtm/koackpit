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
        fastUntil = android.os.SystemClock.elapsedRealtime() + 4000
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

    /**
     * Envoie le fichier de diagnostic directement à l'appli Koackpit Téléphone (partage de connexion) : il
     * arrive dans les Téléchargements du S25 avec une notification pour le partager. Le QR code ouvrait Chrome,
     * qui refusait le téléchargement (27/11). Renvoie le message à afficher.
     */
    fun sendDiag(f: java.io.File): String {
        var r = sendDiagOnce(f)
        // Une seconde tentative si la connexion a lâché (le Wi-Fi de l'autoradio est parfois long à réagir)
        if (r.startsWith("Téléphone injoignable")) { Thread.sleep(2000); r = sendDiagOnce(f).let { if (it.startsWith("Envoyé")) it else "$it (2 essais)" } }
        return r
    }

    private fun sendDiagOnce(f: java.io.File): String {
        val gw = gateway() ?: return "Pas de Wi-Fi : active le partage de connexion du S25."
        return runCatching {
            val c = (URL("http://$gw:$PORT/diag?name=${f.name}").openConnection() as HttpURLConnection)
            try {
                c.connectTimeout = 3000; c.readTimeout = 60_000; c.doOutput = true; c.requestMethod = "POST"
                c.setFixedLengthStreamingMode(f.length())
                c.setRequestProperty("Content-Type", "application/zip")
                c.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
                when (c.responseCode) {
                    200 -> "Envoyé sur le S25 : touche la notification."
                    404 -> "Le téléphone n'a pas pu le recevoir : mets à jour Koackpit Téléphone (1.7)."
                    else -> "Le téléphone n'a pas pu le recevoir : " +
                        (runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(120)?.ifBlank { null } ?: "erreur ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }.getOrElse { "Téléphone injoignable (${it.javaClass.simpleName} : ${it.message.orEmpty().take(80)}) : utilise le QR code." }
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
                    // Premier contact, reprise après plus d'une minute sans téléphone (veille de l'autoradio, coupure
                    // du Wi-Fi) ou appli du téléphone relancée : on se recale sans rien afficher. Sinon, au démarrage,
                    // les messages reçus (et déjà lus) pendant que la voiture dormait réapparaissaient (constaté le 19/11).
                    val gap = android.os.SystemClock.elapsedRealtime() - lastOk
                    if (notifSeq < 0 || gap > 60_000 || seq < notifSeq) notifSeq = seq
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
                    // Journal radio du téléphone (appli 1.5) : ce que le téléphone a vu des coupures, joint au diagnostic
                    val jseq = js.optInt("jseq", -1)
                    if (jseq >= 0) app?.let { a -> fetchPhoneJournal(a, gw!!, jseq) }
                }
            }
            if (ok) lastOk = android.os.SystemClock.elapsedRealtime()
            // Coupure du Wi-Fi : on garde les dernières infos 3 min (la position continue d'avancer sur place)
            else if (android.os.SystemClock.elapsedRealtime() - lastOk > 180_000) main.post { connected.value = false }
            // 1,5 s quand le téléphone répond ; sinon on retente toutes les 2 s pour reprendre vite
            // Juste après une commande (avance, lecture…) : interrogation rapide pour que la barre suive tout de suite
            val fast = android.os.SystemClock.elapsedRealtime() < fastUntil
            Thread.sleep(if (fast) 250 else if (ok) 1500 else 2000)
        }
    }

    /** Fichier du journal radio du téléphone (releve-telephone.txt dans le diagnostic). */
    fun phoneJournalFile(c: Context) = java.io.File(c.filesDir, "releve-telephone.txt")

    private fun fetchPhoneJournal(a: Context, gw: String, jseq: Int) {
        val p = a.getSharedPreferences("phonelink", Context.MODE_PRIVATE)
        var last = p.getInt("jseq", 0)
        if (jseq < last) last = 0                 // appli du téléphone relancée : son compteur repart de zéro
        if (jseq == last) return
        val arr = get("http://$gw:$PORT/journal?since=$last")?.let { runCatching { org.json.JSONArray(String(it)) }.getOrNull() } ?: return
        val f = phoneJournalFile(a)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val t = o.optString("t")
            sb.append(t).append('\n')
            last = maxOf(last, o.optInt("seq"))
            // Événements qui comptent pour les coupures : aussi dans le journal de Koackpit
            if (t.contains("LIAISON PERDUE") || t.contains("POINT D'ACCÈS") || t.contains("ÉTEINT") || t.contains("BT liaison établie"))
                WakeLog.add(a, "Téléphone (vu à ${t.substringBefore("  ").substringAfter(' ').take(10)}) : " + t.substringAfter("  ").substringBefore("  ["))
        }
        runCatching {
            f.appendText(sb.toString())
            if (f.length() > 400_000) f.writeText(f.readLines().takeLast(2000).joinToString("\n") + "\n")
        }
        p.edit().putInt("jseq", last).apply()
    }

    /**
     * Position actuelle du morceau en ms, d'après le téléphone (exacte, même après une avance ou une reprise
     * en milieu de titre). null si l'appli du téléphone ne répond pas ou ne donne pas la position.
     */
    @Volatile private var lastOk = 0L
    @Volatile private var fastUntil = 0L

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
