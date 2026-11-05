package com.koala.koackpit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat

/** Nécessaire pour lire les infos de lecture (titre, artiste) des autres applis. */
class MediaListener : NotificationListenerService()

/** Lecteur Bluetooth : affiche ce que joue le téléphone et le pilote. */
class MusicActivity : ComponentActivity() {
    private val title = mutableStateOf("")
    private val artist = mutableStateOf("")
    private val art = mutableStateOf<Bitmap?>(null)
    private val playing = mutableStateOf(false)
    private val source = mutableStateOf("")
    private val allowed = mutableStateOf(false)
    private val btInfo = mutableStateOf("")

    private var msm: MediaSessionManager? = null
    private var ctrl: MediaController? = null
    private val listener = ComponentName("com.koala.koackpit", MediaListener::class.java.name)

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = readMeta(m)
        override fun onPlaybackStateChanged(s: PlaybackState?) { playing.value = s?.state == PlaybackState.STATE_PLAYING }
        override fun onSessionDestroyed() { attach(null) }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) wakeBtAudio()
        setContent {
            Player(
                title.value, artist.value, art.value, playing.value,
                listOf(source.value, btInfo.value).filter { it.isNotEmpty() }.joinToString("\n"), allowed.value,
                onAllow = ::openAccess,
                onPrev = { control(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                onPlay = { control(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
                onNext = { control(KeyEvent.KEYCODE_MEDIA_NEXT) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        allowed.value = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (allowed.value && msm == null) runCatching {
            msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm!!.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            pick(msm!!.getActiveSessions(listener))
        }.onFailure { source.value = "Accès refusé : $it" }
    }

    override fun onDestroy() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        attach(null)
        super.onDestroy()
    }

    /**
     * Le son Bluetooth ne sort que si l'appli Bluetooth d'origine bascule l'autoradio sur la source BT.
     * On ouvre sa page musique un instant, puis on revient sur le lecteur Koackpit.
     */
    private fun wakeBtAudio() {
        val pkg = Targets.PHONE.first()
        val acts = runCatching {
            packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
                .map { it.name }
        }.getOrDefault(emptyList())
        if (acts.isEmpty()) { btInfo.value = "Appli Bluetooth d'origine introuvable"; return }
        val music = acts.firstOrNull { n -> listOf("music", "a2dp", "media", "audio").any { n.contains(it, true) } }
        if (music == null) {
            btInfo.value = "Pages BT : " + acts.joinToString(", ") { it.substringAfterLast('.') }
            return
        }
        runCatching {
            startActivity(Intent().setClassName(pkg, music).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            btInfo.value = "Source BT : " + music.substringAfterLast('.')
            android.os.Handler(mainLooper).postDelayed({
                startActivity(Intent(this, MusicActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 1200)
        }.onFailure { btInfo.value = "Ouverture ${music.substringAfterLast('.')} refusée : $it" }
    }

    /** Choisit la session en cours de lecture, sinon la première (Bluetooth en priorité). */
    private fun pick(list: List<MediaController>?) {
        val l = list.orEmpty()
        val best = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.packageName.contains("bt", true) || it.packageName.contains("bluetooth", true) }
            ?: l.firstOrNull()
        if (best?.sessionToken != ctrl?.sessionToken) attach(best)
        if (l.isEmpty()) source.value = "Aucune source active"
    }

    private fun attach(c: MediaController?) {
        ctrl?.unregisterCallback(cb)
        ctrl = c
        c?.registerCallback(cb)
        source.value = c?.packageName ?: ""
        readMeta(c?.metadata)
        playing.value = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    private fun readMeta(m: MediaMetadata?) {
        title.value = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        artist.value = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        art.value = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    /** Commandes : via la session si connue, sinon touche média système (part en Bluetooth). */
    private fun control(key: Int) {
        val c = ctrl
        if (c != null) {
            val t = c.transportControls
            when (key) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (playing.value) t.pause() else t.play()
            }
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private fun openAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { toast(this, "Réglage indisponible sur cet autoradio") }
    }
}

@Composable
fun Player(
    title: String, artist: String, art: Bitmap?, playing: Boolean, source: String, allowed: Boolean,
    onAllow: () -> Unit, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Navy).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Pochette (ou pastille Bluetooth)
        Box(
            Modifier.weight(1f, fill = false).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Card),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("BT", color = Yellow, fontSize = 48.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title.ifEmpty { "Lecteur Bluetooth" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            if (artist.isNotEmpty()) Text(artist, color = Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlButton(Glyph.PREV, 60, false, onPrev)
            CtrlButton(if (playing) Glyph.PAUSE else Glyph.PLAY, 76, true, onPlay)
            CtrlButton(Glyph.NEXT, 60, false, onNext)
        }
        Spacer(Modifier.height(10.dp))
        if (!allowed) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardHi).clickable(onClick = onAllow).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Autoriser l'accès aux notifications pour afficher le titre", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center) }
        } else Text(source, color = Muted, fontSize = 11.sp, maxLines = 4, textAlign = TextAlign.Center)
    }
}

enum class Glyph { PREV, PLAY, PAUSE, NEXT }

/** Boutons dessinés (pas de police d'icônes : rendu identique sur tous les autoradios). */
@Composable
fun CtrlButton(g: Glyph, size: Int, main: Boolean, onClick: () -> Unit) {
    val ink = if (main) Navy else Yellow
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (main) Yellow else Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((size * 0.42f).dp)) {
            val w = this.size.width; val h = this.size.height
            fun tri(x0: Float, x1: Float) = drawPath(Path().apply { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h); close() }, ink)
            when (g) {
                Glyph.PLAY -> tri(w * 0.15f, w)
                Glyph.PAUSE -> { drawRect(ink, Offset(w * 0.12f, 0f), Size(w * 0.28f, h)); drawRect(ink, Offset(w * 0.6f, 0f), Size(w * 0.28f, h)) }
                Glyph.NEXT -> { tri(0f, w * 0.78f); drawRect(ink, Offset(w * 0.8f, 0f), Size(w * 0.2f, h)) }
                Glyph.PREV -> { drawRect(ink, Offset(0f, 0f), Size(w * 0.2f, h)); drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.22f, h / 2); lineTo(w, h); close() }, ink) }
            }
        }
    }
}
