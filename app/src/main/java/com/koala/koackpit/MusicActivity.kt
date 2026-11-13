package com.koala.koackpit

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect

/** Lecteur Bluetooth : affiche ce que joue le téléphone et le pilote, via le service de l'autoradio. */
class MusicActivity : ComponentActivity() {
    companion object {
        private var current: WeakReference<MusicActivity>? = null

        /** Ferme la conduite si elle est ouverte (avant une mise à jour, retour à l'accueil…). */
        fun closeIfOpen() { current?.get()?.quit() }
        fun isOpen() = current?.get()?.isFinishing == false
        @Volatile var shown = false   // lecteur réellement à l'écran

        /** Ferme le lecteur sans arrêter la conduite : elle est relancée proprement (onDestroy). */
        fun restart() { current?.get()?.let { if (!it.isFinishing) it.finishAndRemoveTask() } }
    }

    private val title = mutableStateOf("")
    private val artist = mutableStateOf("")
    private val art = mutableStateOf<Bitmap?>(null)   // pochette : viendra de l'appli compagnon
    private val playing = mutableStateOf(false)
    private val warning = mutableStateOf("")
    private val srcIsBt = mutableStateOf(true)
    private val poll = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { readCar(); poll.postDelayed(this, 1000) }
    }
    private var createdAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.uptimeMillis()
        current = WeakReference(this)
        // Bouton retour = quitter la conduite (ferme l'écran partagé)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent { Scaled {
            var now by remember { mutableStateOf(Date()) }
            LaunchedEffect(Unit) { while (true) { now = Date(); kotlinx.coroutines.delay(5_000) } }
            DrivePanel(
                time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now),
                media = MediaInfo(title.value, artist.value, playing.value, srcIsBt.value, warning.value),
                onPrev = { CarLink.prev() },
                onPlay = { CarLink.playPause() },
                onNext = { CarLink.next() },
                onHome = ::goHome,
                // ✕ : quitte la conduite et revient à l'accueil Koackpit (sinon Waze restait seul en plein écran)
                onClose = ::goHome,
                onPhone = { Apps.open(this, Targets.PHONE) },
                onCar = { Apps.open(this, Targets.CAR_INFO) },
                onApps = ::goHome,
                onBtSource = if (srcIsBt.value) null else ::forceBtSource,
            )
        } }
    }

    override fun onStart() {
        super.onStart()
        poll.post(tick)
    }

    /** Lit l'état réel de l'autoradio : source audio, lecture BT, titre. */
    private fun readCar() {
        if (!CarLink.available()) { warning.value = "Service de l'autoradio injoignable"; return }
        warning.value = ""
        val src = CarLink.currentSource()
        srcIsBt.value = src == null || src == CarLink.SRC_BT
        CarLink.a2dpState()?.let { playing.value = it == 1 }
        CarLink.avrcpInfo()?.let { a ->
            title.value = cleanTag(a.getOrNull(0))
            artist.value = cleanTag(a.getOrNull(1))
        }
    }

    /**
     * Demande la source BT au service de l'autoradio. Il peut ouvrir sa page Bluetooth par-dessus :
     * on ne quitte pas la conduite pendant ce temps, et on remet le lecteur devant.
     */
    private fun forceBtSource() {
        createdAt = SystemClock.uptimeMillis()
        CarLink.enterSource(CarLink.SRC_BT)
        poll.postDelayed({
            startActivity(Intent(this, MusicActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }, 1500)
    }

    /** Écran d'accueil appuyé : la fenêtre du lecteur est réduite à une bande → on quitte. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settled() && awake() && newConfig.screenWidthDp in 1..159) quit()
    }

    /**
     * Lecteur masqué (caméra de recul, appel, appli du fabricant, veille) : on ne ferme JAMAIS la conduite
     * ici. Seuls ✕, retour et les boutons accueil la ferment. Android rétablit l'écran partagé quand
     * l'appli qui recouvrait se ferme ; sinon WakeService le rétablit.
     */
    override fun onStop() {
        super.onStop()
        poll.removeCallbacks(tick)
        shown = false
    }

    override fun onResume() {
        super.onResume()
        shown = true
    }

    /** Quitte la conduite et rouvre l'accueil Koackpit. */
    private fun goHome() {
        val app = applicationContext
        quit {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun settled() = SystemClock.uptimeMillis() - createdAt > 4000

    /** Ferme le lecteur et sa tâche : Android referme alors l'écran partagé. */
    private fun awake() = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive

    /** Fermeture voulue par l'utilisateur : la conduite ne sera pas rétablie au réveil. */
    fun quit(then: (() -> Unit)? = null) {
        Prefs.setDriving(this, false)
        if (!isFinishing) finishAndRemoveTask()
        // Le GPS est la fenêtre principale : on referme l'écran partagé nous-mêmes
        SplitScreen.dismiss(applicationContext, then)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        shown = false
        // Fermé sans demande de l'utilisateur (arrêt par le système, rétablissement) : on relance la conduite
        if (Prefs.driving(this) && awake() && !isChangingConfigurations) {
            WakeLog.add(this, "Lecteur fermé par le système : conduite relancée")
            WakeService.relaunchDriving(applicationContext, 1500)
        }
        poll.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
fun Player(
    title: String, artist: String, art: Bitmap?, playing: Boolean, warning: String,
    onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onClose: () -> Unit, onHome: () -> Unit,
    onBtSource: (() -> Unit)? = null,
) {
  Box(Modifier.fillMaxSize().background(Navy)) {
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
        if (onBtSource != null) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Yellow).clickable(onClick = onBtSource).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Activer la source Bluetooth", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(6.dp))
        }
        if (warning.isNotEmpty()) Text(warning, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
    // Retour à l'accueil Koackpit
    Box(
        Modifier.align(Alignment.TopStart).padding(10.dp).size(48.dp).clip(CircleShape).background(Card).clickable(onClick = onHome),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height
            drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h * 0.45f); lineTo(0f, h * 0.45f); close() }, Yellow)
            drawRect(Yellow, Offset(w * 0.18f, h * 0.45f), Size(w * 0.64f, h * 0.55f))
            drawRect(Card, Offset(w * 0.4f, h * 0.65f), Size(w * 0.2f, h * 0.35f))
        }
    }
    // Quitter la conduite
    Box(
        Modifier.align(Alignment.TopEnd).padding(10.dp).size(48.dp).clip(CircleShape).background(Card).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            drawLine(Yellow, Offset(0f, 0f), Offset(w, w), strokeWidth = w * 0.16f)
            drawLine(Yellow, Offset(w, 0f), Offset(0f, w), strokeWidth = w * 0.16f)
        }
    }
  }
}

enum class Glyph { PREV, PLAY, PAUSE, NEXT }

/** Boutons dessinés (pas de police d'icônes : rendu identique sur tous les autoradios). */
@Composable
fun CtrlButton(g: Glyph, size: Int, main: Boolean, onClick: () -> Unit) {
    val ink = if (main) Navy else Yellow
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (main) Yellow else CardHi).clickable(onClick = onClick),
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

/** État musique lu dans le service de l'autoradio, rafraîchi chaque seconde (pour l'accueil). */
@Composable
fun rememberMedia(): MediaInfo {
    var m by remember { mutableStateOf(MediaInfo()) }
    LaunchedEffect(Unit) {
        while (true) {
            m = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val a = CarLink.avrcpInfo()
                    MediaInfo(
                        title = cleanTag(a?.getOrNull(0)),
                        artist = cleanTag(a?.getOrNull(1)),
                        playing = CarLink.a2dpState() == 1,
                    )
                }.getOrDefault(MediaInfo())
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    return m
}

/** Le Bluetooth envoie « Not Provided » / « Unknown » quand le téléphone ne donne pas l'info. */
fun cleanTag(v: String?): String {
    val t = v?.trim().orEmpty()
    return if (t.equals("Not Provided", true) || t.equals("Unknown", true) || t.startsWith("<unknown", true)) "" else t
}
