package com.koala.koackpit.phone

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lit le lecteur en cours sur le téléphone (Deezer, Spotify…) et le met à disposition de Koackpit.
 *
 * Koackpit (sur l'autoradio, connecté au partage de connexion du téléphone) interroge ce petit serveur :
 *   GET /state → {"title","artist","album","app","playing","art","pos","dur"}   (art = empreinte de la pochette,
 *                pos = position dans le morceau en ms au moment de la réponse, dur = durée en ms)
 *   GET /art   → pochette en JPEG
 *   GET /notifs?since=N → messages et appels manqués reçus depuis N (numéro croissant)
 *   GET /cmd?a=toggle|play|pause|next|prev|seek&ms=… → commande du lecteur en cours
 * /state donne aussi la batterie du téléphone et sa position GPS.
 * Rien n'est envoyé ailleurs : seul l'autoradio, connecté au partage de connexion, interroge ce serveur.
 */
class MediaBridge : NotificationListenerService() {
    companion object {
        const val PORT = 47800
        @Volatile var running = false
        @Volatile var lastClient = 0L          // dernière visite de l'autoradio (pour l'écran d'état)
        @Volatile var lastClientIp = ""
        @Volatile var title = ""; @Volatile var artist = ""; @Volatile var album = ""; @Volatile var app = ""
        @Volatile var playing = false
        @Volatile var art: ByteArray? = null
        @Volatile var artId = ""
        // Position de lecture (pour les paroles synchronisées sur l'autoradio)
        @Volatile var pos = 0L; @Volatile var posAt = 0L; @Volatile var speed = 1f; @Volatile var dur = 0L

        // Notifications utiles en voiture (messages, appels manqués) : les 20 dernières
        data class Notif(val seq: Int, val app: String, val appName: String, val title: String, val text: String, val kind: String, val time: Long)
        val notifs = java.util.concurrent.CopyOnWriteArrayList<Notif>()
        @Volatile var notifSeq = 0
        // Position GPS du téléphone
        @Volatile var loc: android.location.Location? = null

        /** Position actuelle en ms (Android donne la position au dernier changement + l'heure de ce changement). */
        fun positionNow(): Long {
            if (posAt <= 0L) return pos
            val p = if (playing) pos + ((android.os.SystemClock.elapsedRealtime() - posAt) * speed).toLong() else pos
            return if (dur > 0) p.coerceIn(0, dur) else maxOf(0, p)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private var server: ServerSocket? = null

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() = pick()
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    private val locListener = object : android.location.LocationListener {
        override fun onLocationChanged(l: android.location.Location) { loc = l }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @Volatile private var locStarted = false

    /** Aussi relancée à chaque visite de l'autoradio : la position peut avoir été autorisée après coup. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locStarted) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        locStarted = true
        runCatching {
            getSystemService(android.location.LocationManager::class.java)
                .requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        }
    }

    // ---- Notifications : messages et appels manqués ----
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        runCatching {
            val n = sbn.notification
            if (sbn.isOngoing || (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return
            if (sbn.packageName == packageName) return
            val missed = n.category == "missed_call" ||
                (sbn.packageName.contains("dialer") || sbn.packageName.contains("telecom") || sbn.packageName.contains("incallui")) &&
                n.category != android.app.Notification.CATEGORY_CALL
            val msg = n.category == android.app.Notification.CATEGORY_MESSAGE
            if (!missed && !msg) return
            val ex = n.extras
            // Messages groupés (WhatsApp…) : le dernier message de la conversation
            val lines = ex.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
            val lastMsg = (lines?.lastOrNull() as? android.os.Bundle)
            val title = (ex.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)
                ?: ex.getCharSequence(android.app.Notification.EXTRA_TITLE))?.toString().orEmpty()
            val sender = lastMsg?.getCharSequence("sender")?.toString()
            val text = (lastMsg?.getCharSequence("text") ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrDefault(sbn.packageName)
            val shownTitle = if (sender != null && sender != title && title.isNotEmpty()) "$sender · $title" else (sender ?: title)
            // Même message déjà reçu (Android renvoie souvent la notification mise à jour) : ignoré
            if (notifs.any { it.app == sbn.packageName && it.title == shownTitle && it.text == text }) return
            val seq = notifSeq + 1
            notifs.add(Notif(seq, sbn.packageName, appName, shownTitle, text.take(300), if (missed) "call" else "msg", System.currentTimeMillis()))
            while (notifs.size > 20) notifs.removeAt(0)
            notifSeq = seq
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (level >= 0) level * 100 / scale else -1) to plugged
    }

    /**
     * Commande du lecteur. Constats du 18/11 : l'avance marche écran verrouillé, mais Deezer l'ignore parfois
     * téléphone déverrouillé ; l'envoyer à toutes ses sessions d'un coup empirait les choses (1.3).
     * Donc : d'abord la session suivie seule (comme en 1.2) ; si 1 s plus tard la position n'a pas bougé,
     * on essaie les autres sessions de la même appli, une par une.
     */
    private fun command(a: String, ms: Long) {
        val cur = current ?: return
        main.post {
            runCatching {
                val tc = cur.transportControls
                when (a) {
                    "play" -> tc.play(); "pause" -> tc.pause(); "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious()
                    "toggle" -> if (playing) tc.pause() else tc.play()
                    "seek" -> { tc.seekTo(ms); retrySeek(cur, ms, 0) }
                }
            }
        }
    }

    private fun retrySeek(first: MediaController, ms: Long, i: Int) {
        main.postDelayed({
            read()
            if (kotlin.math.abs(positionNow() - ms) <= 3000) return@postDelayed
            val others = runCatching { msm?.getActiveSessions(ComponentName(this, MediaBridge::class.java)) }.getOrNull().orEmpty()
                .filter { it.packageName == first.packageName && it.sessionToken != first.sessionToken }
            val c = others.getOrNull(i) ?: return@postDelayed
            runCatching { c.transportControls.seekTo(ms) }
            retrySeek(first, ms, i + 1)
        }, 1000)
    }

    override fun onListenerConnected() {
        running = true
        startLocation()
        msm = getSystemService(MediaSessionManager::class.java)
        runCatching { msm?.addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaBridge::class.java), main) }
        pick()
        startServer()
        RadioWatch.start(applicationContext)
        main.removeCallbacks(updateCheck); main.postDelayed(updateCheck, 60_000)
    }

    /** Recherche de mise à jour toutes les 6 h (notification si une nouvelle version existe). */
    private val updateCheck = object : Runnable {
        override fun run() {
            Thread { runCatching { PhoneUpdate.backgroundCheck(applicationContext) } }.start()
            main.postDelayed(this, 6 * 3600_000L)
        }
    }

    override fun onListenerDisconnected() {
        running = false
        main.removeCallbacks(updateCheck)
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }
        current?.unregisterCallback(cb); current = null
        runCatching { getSystemService(android.location.LocationManager::class.java).removeUpdates(locListener) }
        locStarted = false
        runCatching { server?.close() }; server = null
        // Android peut déconnecter le service : on redemande à être relié
        runCatching { requestRebind(ComponentName(this, MediaBridge::class.java)) }
    }

    /** Choisit le lecteur à suivre : celui qui joue, sinon le plus récent. */
    private fun pick() {
        val list = runCatching { msm?.getActiveSessions(ComponentName(this, MediaBridge::class.java)) }.getOrNull().orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(cb)
            current = best
            best?.registerCallback(cb, main)
        }
        read()
    }

    private fun read() {
        val c = current
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        app = c?.packageName.orEmpty()
        playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        c?.playbackState?.let { st ->
            pos = st.position; posAt = st.lastPositionUpdateTime; speed = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
        }
        dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val id = if (bmp == null) "" else "$title|$album|${bmp.width}x${bmp.height}".hashCode().toString(16)
        if (id != artId) {
            art = bmp?.let { jpeg(it) }
            artId = if (art == null) "" else id
        }
    }

    /** Pochette réduite à 500 px max (légère à transférer, nette sur l'autoradio). */
    private fun jpeg(b: Bitmap): ByteArray? = runCatching {
        val s = 500f / maxOf(b.width, b.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }.getOrNull()

    private fun startServer() {
        if (server != null) return
        Thread {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = ss
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    Thread { serve(s) }.start()
                }
            } catch (_: Exception) {
                server = null
            }
        }.start()
    }

    private fun serve(s: Socket) {
        s.use {
            runCatching {
                s.soTimeout = 3000
                // En-têtes lus octet par octet : le corps d'un envoi (POST /diag) reste intact derrière
                val ins = java.io.BufferedInputStream(s.getInputStream())
                fun rl(): String? {
                    val b = ByteArrayOutputStream()
                    while (true) {
                        val c = ins.read()
                        if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                        if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
                        b.write(c)
                        if (b.size() > 8192) return null
                    }
                }
                val line = rl() ?: return
                var bodyLen = 0L
                while (true) {
                    val h = rl() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", true)) bodyLen = h.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                lastClient = System.currentTimeMillis(); lastClientIp = s.inetAddress.hostAddress.orEmpty()
                val path = line.split(' ').getOrNull(1).orEmpty()
                val out = s.getOutputStream()
                fun reply(type: String, body: ByteArray) {
                    out.write("HTTP/1.0 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush()
                }
                when {
                    path.startsWith("/state") -> { if (!locStarted) main.post { startLocation() }; reply("application/json", JSONObject().apply {
                        put("v", 1); put("title", title); put("artist", artist); put("album", album)
                        put("app", app); put("playing", playing); put("art", artId)
                        put("pos", positionNow()); put("dur", dur)
                        val (b, ch) = battery(); put("batt", b); put("charging", ch)
                        put("notif", notifSeq)
                        put("jseq", RadioWatch.seq)
                        loc?.let { l ->
                            put("loc", JSONObject().apply {
                                put("lat", l.latitude); put("lon", l.longitude); put("acc", l.accuracy.toDouble())
                                put("speed", if (l.hasSpeed()) l.speed.toDouble() else -1.0)
                                put("bearing", if (l.hasBearing()) l.bearing.toDouble() else -1.0)
                                put("age", (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000)
                            })
                        }
                    }.toString().toByteArray()) }
                    path.startsWith("/notifs") -> {
                        val since = Regex("since=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val arr = org.json.JSONArray()
                        for (n in notifs) if (n.seq > since) arr.put(JSONObject().apply {
                            put("seq", n.seq); put("app", n.app); put("appName", n.appName); put("title", n.title)
                            put("text", n.text); put("kind", n.kind); put("time", n.time)
                        })
                        reply("application/json", arr.toString().toByteArray())
                    }
                    path.startsWith("/journal") -> {
                        val since = Regex("since=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val arr = org.json.JSONArray()
                        for (l in RadioWatch.since(since)) arr.put(JSONObject().apply { put("seq", l.seq); put("t", l.text) })
                        reply("application/json", arr.toString().toByteArray())
                    }
                    path.startsWith("/cmd") -> {
                        val a = Regex("a=(\\w+)").find(path)?.groupValues?.get(1).orEmpty()
                        val ms = Regex("ms=(\\d+)").find(path)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        command(a, ms)
                        reply("text/plain", "OK".toByteArray())
                    }
                    line.startsWith("POST") && path.startsWith("/diag") && bodyLen > 0 -> {
                        s.soTimeout = 30_000
                        val ok = DiagReceive.save(this, ins, bodyLen)
                        if (ok) reply("text/plain", "OK".toByteArray())
                        else out.write("HTTP/1.0 500 Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                    path.startsWith("/art") && art != null -> reply("image/jpeg", art!!)
                    else -> out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
    }
}
