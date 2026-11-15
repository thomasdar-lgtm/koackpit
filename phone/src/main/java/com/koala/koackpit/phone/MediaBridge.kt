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
 *   GET /state → {"title","artist","album","app","playing","art"}   (art = empreinte de la pochette)
 *   GET /art   → pochette en JPEG
 * Rien n'est envoyé ailleurs ; seules ces deux lectures sont possibles.
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

    override fun onListenerConnected() {
        running = true
        msm = getSystemService(MediaSessionManager::class.java)
        runCatching { msm?.addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaBridge::class.java), main) }
        pick()
        startServer()
    }

    override fun onListenerDisconnected() {
        running = false
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }
        current?.unregisterCallback(cb); current = null
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
                val line = s.getInputStream().bufferedReader().readLine() ?: return
                lastClient = System.currentTimeMillis(); lastClientIp = s.inetAddress.hostAddress.orEmpty()
                val path = line.split(' ').getOrNull(1).orEmpty()
                val out = s.getOutputStream()
                fun reply(type: String, body: ByteArray) {
                    out.write("HTTP/1.0 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body); out.flush()
                }
                when {
                    path.startsWith("/state") -> reply("application/json", JSONObject().apply {
                        put("v", 1); put("title", title); put("artist", artist); put("album", album)
                        put("app", app); put("playing", playing); put("art", artId)
                    }.toString().toByteArray())
                    path.startsWith("/art") && art != null -> reply("image/jpeg", art!!)
                    else -> out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
    }
}
