package com.koala.koackpit

import android.content.Context
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableLongStateOf
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * La Golf 5 « en 3D » : vues pré-calculées (depuis 0.17.5).
 *
 * La puce graphique de l'autoradio (PowerVR G6110, pilote Rockchip de 2018) n'affiche correctement aucune 3D
 * calculée en direct : page web WebGL (jamais affichée), Filament (images noires ou découpées, 4 façons essayées
 * le 23/11). La voiture est donc dessinée à l'avance, en pleine qualité, depuis 72 angles (tous les 5°) fermée et
 * 36 angles pour chaque ouvrant (web/golf3d/render-views.py, même modèle et même éclairage que la page web).
 * Un doigt fait tourner (les images défilent), deux doigts zooment, un appui ouvre la fiche de la pièce touchée
 * (carte des pièces calculée avec chaque vue). Aucune 3D ni puce graphique sollicitée.
 * Ouvrants : trame CAN « portes » ([Doors]) ; un seul ouvert → sa vue, plusieurs → tout ouvert.
 */
@Composable
fun Golf3D(mask: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val views = remember { GolfViews.get(ctx) }
    if (views == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Vue de la voiture indisponible", color = Muted, fontSize = 12.sp) }
        return
    }
    val state = views.stateFor(mask)
    var rot by remember { mutableFloatStateOf(0f) }        // degrés, 0 = 3/4 avant
    var zoom by remember { mutableFloatStateOf(1f) }
    var moving by remember { mutableStateOf(false) }        // doigt en mouvement : images légères
    var lastMove by remember { mutableLongStateOf(0L) }
    val panel = remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(state) { panel.value = null; views.warm(state) }
    // Doigt immobile 150 ms → image en pleine définition
    LaunchedEffect(lastMove) { if (moving) { delay(150); moving = false; views.endDrag() } }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        Crossfade(state, animationSpec = tween(350), label = "ouvrants") { st ->
            val idx = views.index(st, rot)
            // Pleine définition seulement à l'arrêt (décodée à part) ; en tournant, les vues légères déjà en mémoire
            // 1re image tout de suite (comme avant), le temps que les vues légères se préparent
            val full = remember(st) { mutableStateOf(views.loadFull(st, idx)?.let { idx to it }) }
            views.version.intValue   // relit quand de nouvelles vues légères sont prêtes
            LaunchedEffect(st, idx, moving) {
                if (!moving && full.value?.first != idx) {
                    withContext(Dispatchers.IO) { views.loadFull(st, idx) }?.let { full.value = idx to it }
                }
            }
            val img = full.value?.takeIf { it.first == idx && !moving }?.second ?: views.low(st, idx)
            if (img != null) {
                Image(
                    img, "Ma Golf", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                )
            }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, 1.6f)
                        if (pan.x != 0f) {
                            rot -= pan.x / with(density) { 3.dp.toPx() }   // ~1° par 3 dp de doigt
                            if (!moving) views.startDrag()
                            moving = true; lastMove = System.nanoTime()
                            views.shown(state, views.index(state, rot))
                        }
                        if (pan.x != 0f || z != 1f) panel.value = null
                    }
                }
                .pointerInput(state) {
                    detectTapGestures { o ->
                        // Point touché → point de l'image (affichée « Fit » au centre, puis zoomée au centre)
                        val s = minOf(boxW / views.w, boxH / views.h) * zoom
                        val ix = (o.x - boxW / 2) / s + views.w / 2f
                        val iy = (o.y - boxH / 2) / s + views.h / 2f
                        panel.value = views.pick(state, views.index(state, rot), ix / views.w, iy / views.h)
                    }
                },
        )
        panel.value?.let { (title, text) ->
            Column(
                Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(14.dp)).cardBg(RoundedCornerShape(14.dp)).padding(12.dp),
            ) {
                Text(title, color = AccentText, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(text, color = Ink, fontSize = 12.sp, lineHeight = 16.sp)
                Text(
                    "Fermer", color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Yellow)
                        .clickable { panel.value = null }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            "Modèle 3D : bimboit34 (Sketchfab) · CC BY 4.0", color = Muted.copy(alpha = 0.7f), fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

/** Vues pré-calculées (assets/golf/views) : images, cartes des pièces, fiches. */
internal class GolfViews(private val app: Context, meta: JSONObject) {
    val w = meta.getInt("w"); val h = meta.getInt("h")
    private val pw = meta.getInt("pw"); private val ph = meta.getInt("ph")
    private val angles = meta.getJSONObject("angles")
    private val infos = meta.getJSONArray("infos")
    /**
     * Fluidité (0.19.1) : avant, chaque nouvel angle était décodé pendant qu'on tournait (image 1280×640, lente à
     * décoder sur l'autoradio) → saccades. Maintenant, toutes les vues d'un état sont décodées UNE fois, en demi-
     * définition (640×320, 0,8 Mo), en arrière-plan, de la plus proche à la plus lointaine, et gardées en mémoire
     * (fermée : 72 vues ≈ 59 Mo ; un ouvrant : 36 vues ≈ 30 Mo, un seul à la fois). En tournant, on n'affiche que
     * celles-là ; la vue en pleine définition n'est décodée qu'une fois le doigt arrêté.
     */
    private val lows = HashMap<String, Array<ImageBitmap?>>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "golf-vues").apply { priority = Thread.MIN_PRIORITY + 1 } }
    @Volatile private var center = 0
    val version = androidx.compose.runtime.mutableIntStateOf(0)
    private val pickMaps = LruCache<String, ByteArray>(8)
    private var fullCache: Triple<String, Int, ImageBitmap>? = null

    fun angles(state: String) = angles.optInt(state, angles.getInt("0"))

    /** Image la plus proche de l'angle (degrés). */
    fun index(state: String, rot: Float): Int {
        val n = angles(state)
        return (((rot % 360f + 360f) % 360f) / 360f * n).roundToInt() % n
    }

    fun stateFor(mask: Int): String {
        val m = mask and 63
        return when {
            m == 0 -> "0"
            Integer.bitCount(m) > 1 -> "ALL"
            else -> when (m) { Doors.FL -> "FL"; Doors.FR -> "FR"; Doors.RL -> "RL"; Doors.RR -> "RR"; Doors.TRUNK -> "TAIL"; else -> "HOOD" }
        }
    }

    private fun decode(state: String, i: Int, sample: Int): Bitmap? = runCatching {
        app.assets.open("golf/views/v${state}_$i.webp").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Décode en arrière-plan toutes les vues légères de l'état (une seule fois). Libère les autres ouvrants. */
    fun warm(state: String) {
        synchronized(lows) {
            if (lows.containsKey(state)) return
            lows.keys.filter { it != "0" && it != state }.forEach { lows.remove(it) }
            lows[state] = arrayOfNulls(angles(state))
        }
        worker.execute {
            val arr = synchronized(lows) { lows[state] } ?: return@execute
            val n = arr.size; val t0 = SystemClock.elapsedRealtime()
            val done = BooleanArray(n); var ok = 0
            repeat(n) {
                // La plus proche de l'angle affiché d'abord (l'angle peut changer pendant le chargement)
                val c = center.coerceIn(0, n - 1)
                var i = -1
                for (d in 0..n / 2) { val a = (c + d) % n; val b = (c - d + n) % n; if (!done[a]) { i = a; break }; if (!done[b]) { i = b; break } }
                if (i < 0) return@repeat
                done[i] = true
                if (synchronized(lows) { lows[state] } !== arr) return@execute   // état libéré entre-temps
                decode(state, i, 2)?.let { b -> b.prepareToDraw(); arr[i] = b.asImageBitmap(); ok++ }
                if (ok % 6 == 0) version.intValue++
            }
            version.intValue++
            val ms = SystemClock.elapsedRealtime() - t0
            WakeLog.add(app, "Vue voiture : $ok/$n vues « $state » prêtes en ${ms / 100 / 10.0} s (${if (ok > 0) ms / ok else 0} ms par vue)")
        }
    }

    /** Vue légère de l'angle, sinon la plus proche déjà prête (jamais d'attente pendant la rotation). */
    fun low(state: String, i: Int): ImageBitmap? {
        val arr = synchronized(lows) { lows[state] } ?: return null
        val n = arr.size
        for (d in 0..n / 2) { arr[(i + d) % n]?.let { if (d > 0) missed++; return it }; arr[(i - d + n) % n]?.let { missed++; return it } }
        return null
    }

    /** Vue en pleine définition (une seule gardée). */
    fun loadFull(state: String, i: Int): ImageBitmap? {
        fullCache?.let { (s, k, b) -> if (s == state && k == i) return b }
        val b = decode(state, i, 1)?.also { it.prepareToDraw() }?.asImageBitmap() ?: return null
        fullCache = Triple(state, i, b)
        return b
    }

    // Mesure de la rotation, écrite au journal à chaque lâcher du doigt
    private var dragT0 = 0L; private var dragViews = 0; private var lastIdx = -1; @Volatile private var missed = 0
    fun startDrag() { dragT0 = SystemClock.elapsedRealtime(); dragViews = 0; missed = 0; lastIdx = -1 }
    fun shown(state: String, i: Int) { center = i; if (i != lastIdx) { dragViews++; lastIdx = i } }
    fun endDrag() {
        val s = (SystemClock.elapsedRealtime() - dragT0 - 150) / 1000.0
        if (dragViews >= 5) WakeLog.add(app, "Vue voiture : rotation, $dragViews vues en ${"%.1f".format(s)} s, $missed fois une vue voisine (pas encore prête)")
    }

    /** Fiche de la pièce au point (fx, fy) de l'image (0..1), ou null. */
    fun pick(state: String, i: Int, fx: Float, fy: Float): Pair<String, String>? {
        if (fx !in 0f..1f || fy !in 0f..1f) return null
        val k = "$state/$i"
        val map = pickMaps.get(k) ?: runCatching {
            val b = app.assets.open("golf/views/p${state}_$i.png").use { BitmapFactory.decodeStream(it) }
            val px = IntArray(pw * ph); b.getPixels(px, 0, pw, 0, 0, pw, ph)
            ByteArray(pw * ph) { (px[it] and 0xff).toByte() }
        }.getOrNull()?.also { pickMaps.put(k, it) } ?: return null
        val cx = (fx * pw).toInt().coerceIn(0, pw - 1); val cy = (fy * ph).toInt().coerceIn(0, ph - 1)
        // Le point exact, sinon le plus proche à 2 points près (doigt imprécis, petites pièces)
        var id = 0
        loop@ for (r in 0..2) for (dy in -r..r) for (dx in -r..r) {
            val x = cx + dx; val y = cy + dy
            if (x in 0 until pw && y in 0 until ph) { id = map[y * pw + x].toInt() and 0xff; if (id != 0) break@loop }
        }
        if (id == 0 || id > infos.length()) return null
        val o = infos.getJSONObject(id - 1)
        return o.getString("title") to o.getString("text")
    }

    companion object {
        @Volatile private var inst: GolfViews? = null
        /** Préparation des vues de la voiture fermée, au démarrage (en fond, basse priorité). */
        fun prewarm(c: Context) { get(c)?.warm("0") }
        fun get(c: Context): GolfViews? = inst ?: runCatching {
            GolfViews(c.applicationContext, JSONObject(c.assets.open("golf/views/views.json").bufferedReader().readText()))
        }.onFailure { WakeLog.add(c, "Vue voiture : fichiers absents (${it.message})") }.getOrNull()?.also { inst = it }
    }
}
