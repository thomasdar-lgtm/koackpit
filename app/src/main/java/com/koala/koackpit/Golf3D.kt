package com.koala.koackpit

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import java.io.File
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChanged
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
 * La Golf 5 « en 3D » : vues pré-calculées (depuis 0.17.5), dans un pack d'images téléchargé à part ([GolfPack], 0.20.0).
 *
 * La puce graphique de l'autoradio (PowerVR G6110, pilote Rockchip de 2018) n'affiche correctement aucune 3D
 * calculée en direct : page web WebGL (jamais affichée), Filament (images noires ou découpées, 4 façons essayées
 * le 23/11). La voiture est donc dessinée à l'avance (web/golf3d, workflow Render views) depuis 72 angles (tous
 * les 5°), à 2 hauteurs (vue de départ, vue de dessus), pour 12 états d'ouvrants : fermée, chaque ouvrant seul,
 * conducteur + coffre, les deux avant, conducteur + arrière gauche, les 4 portes, tout ouvert.
 * Un doigt fait tourner (glisser à l'horizontale) ou change de hauteur (glisser franchement vers le bas / le haut,
 * ou le bouton), deux doigts zooment (×2,5) et déplacent l'image, un appui ouvre la fiche de la pièce touchée.
 * Ouvrants : trame CAN « portes » ([Doors]) ; combinaison non dessinée → la plus proche qui contient tout.
 */
@Composable
fun Golf3D(mask: Int, modifier: Modifier = Modifier, startZoom: Float = 1f, startOff: Offset = Offset.Zero, startElev: Int = 0) {
    val ctx = LocalContext.current
    GolfViews.version.intValue   // relit quand le pack arrive
    val views = GolfViews.get(ctx)
    if (views == null) { PackWaiting(modifier); return }
    val state = views.stateFor(mask)
    var elev by remember { mutableIntStateOf(startElev.coerceIn(0, views.elevs - 1)) }
    var rot by remember { mutableFloatStateOf(0f) }        // degrés, 0 = 3/4 avant
    var zoom by remember { mutableFloatStateOf(startZoom) }
    var offX by remember { mutableFloatStateOf(startOff.x) }  // déplacement de l'image zoomée (deux doigts)
    var offY by remember { mutableFloatStateOf(startOff.y) }
    var moving by remember { mutableStateOf(false) }        // doigt en mouvement : images légères
    var lastMove by remember { mutableLongStateOf(0L) }
    val panel = remember { mutableStateOf<Pair<String, String>?>(null) }
    val key = "${state}_$elev"
    LaunchedEffect(key) { panel.value = null; views.warm(state, elev) }
    // Doigt immobile 150 ms → image en pleine définition
    LaunchedEffect(lastMove) { if (moving) { delay(150); moving = false; views.endDrag() } }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        Crossfade(state to elev, animationSpec = tween(350), label = "ouvrants") { (st, el) ->
            val idx = views.index(st, rot)
            // Pleine définition seulement à l'arrêt (décodée à part) ; en tournant, les vues légères déjà en mémoire.
            // 1re image tout de suite, le temps que les vues légères se préparent.
            val full = remember(st, el) { mutableStateOf(views.loadFull(st, el, idx)?.let { idx to it }) }
            views.lowVersion.intValue   // relit quand de nouvelles vues légères sont prêtes
            LaunchedEffect(st, el, idx, moving) {
                if (!moving && full.value?.first != idx) {
                    withContext(Dispatchers.IO) { views.loadFull(st, el, idx) }?.let { full.value = idx to it }
                }
            }
            val img = full.value?.takeIf { it.first == idx && !moving }?.second ?: views.low(st, el, idx)
            if (img != null) {
                Image(
                    img, "Ma Golf", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offX; translationY = offY },
                )
            }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(state, elev, boxW, boxH) {
                    // Un doigt : tourne (horizontal) ou change de hauteur (vertical franc). Deux doigts : pincer pour
                    // zoomer (jusqu'à ×2,5) et glisser pour se déplacer dans l'image zoomée. Dézoomé à fond, recentrée.
                    val slop = viewConfiguration.touchSlop
                    val step = 70.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var past = false; var moved = Offset.Zero; var vert = 0f; var horiz = 0f
                        do {
                            val ev = awaitPointerEvent()
                            val fingers = ev.changes.count { it.pressed }
                            val z = ev.calculateZoom(); val pan = ev.calculatePan()
                            if (!past) { moved += pan; if (moved.getDistance() > slop || z != 1f) past = true }
                            if (past && fingers > 0) {
                                if (fingers >= 2) {
                                    zoom = (zoom * z).coerceIn(1f, 2.5f)
                                    val mx = (zoom - 1f) * boxW / 2; val my = (zoom - 1f) * boxH / 2
                                    offX = (offX + pan.x).coerceIn(-mx, mx); offY = (offY + pan.y).coerceIn(-my, my)
                                    if (zoom <= 1.01f) { offX = 0f; offY = 0f }
                                } else {
                                    vert += pan.y; horiz += kotlin.math.abs(pan.x)
                                    // Geste surtout vertical : vue de dessus (vers le bas) / vue de départ (vers le haut)
                                    if (kotlin.math.abs(vert) > step && kotlin.math.abs(vert) > horiz * 1.5f) {
                                        val e = if (vert > 0) 1 else 0
                                        if (e < views.elevs && e != elev) elev = e
                                        vert = 0f; horiz = 0f
                                    }
                                    if (pan.x != 0f) {
                                        rot -= pan.x / with(density) { 3.dp.toPx() }   // ~1° par 3 dp de doigt
                                        if (!moving) views.startDrag()
                                        moving = true; lastMove = System.nanoTime()
                                        views.shown(views.index(state, rot))
                                    }
                                }
                                panel.value = null
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(state, elev) {
                    detectTapGestures { o ->
                        // Point touché → point de l'image (affichée « Fit » au centre, puis zoomée au centre)
                        val s = minOf(boxW / views.w, boxH / views.h) * zoom
                        val ix = (o.x - boxW / 2 - offX) / s + views.w / 2f
                        val iy = (o.y - boxH / 2 - offY) / s + views.h / 2f
                        panel.value = views.pick(state, elev, views.index(state, rot), ix / views.w, iy / views.h)
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
        // Bouton de hauteur (le geste vertical marche aussi)
        if (views.elevs > 1) Text(
            if (elev == 0) "Vue de dessus" else "Vue de côté", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(50)).darkBtnBg(RoundedCornerShape(50))
                .clickable { elev = 1 - elev; panel.value = null }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Text(
            "Modèle 3D : bimboit34 (Sketchfab) · CC BY 4.0", color = Muted.copy(alpha = 0.7f), fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

/** Pack d'images pas encore là : téléchargement (une seule fois) et avancement. */
@Composable
private fun PackWaiting(modifier: Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { GolfPack.ensure(ctx) }
    val p = GolfPack.progress.intValue
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Images de la voiture", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(
            when {
                p in 0..100 -> "Téléchargement : $p %"
                p == 101 -> "Préparation…"
                else -> GolfPack.problem.value ?: "En attente de connexion"
            },
            color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Text("Une seule fois, environ 40 Mo", color = Muted, fontSize = 12.sp)
    }
}

/** Vues pré-calculées du pack : images, cartes des pièces, fiches. */
internal class GolfViews(private val dir: File, meta: JSONObject) {
    val w = meta.getInt("w"); val h = meta.getInt("h")
    private val pw = meta.getInt("pw"); private val ph = meta.getInt("ph")
    private val angles = meta.getJSONObject("angles")
    private val infos = meta.getJSONArray("infos")
    val elevs = meta.optJSONArray("elevs")?.length() ?: 1
    /** États dessinés et leurs ouvrants. */
    private val masks = linkedMapOf("0" to 0, "FL" to 1, "FR" to 2, "RL" to 4, "RR" to 8, "TAIL" to 16, "HOOD" to 32,
        "FL_TAIL" to 17, "FL_FR" to 3, "FL_RL" to 5, "DOORS" to 15, "ALL" to 63).filterKeys { angles.has(it) }
    /**
     * Fluidité (0.19.1) : toutes les vues d'un état × hauteur sont décodées UNE fois, en demi-définition (640×320,
     * 0,8 Mo), en arrière-plan, de la plus proche à la plus lointaine, et gardées en mémoire (72 vues ≈ 59 Mo).
     * Gardées : voiture fermée vue de départ + la vue affichée. En tournant, on n'affiche que celles-là ; la
     * pleine définition n'est décodée qu'une fois le doigt arrêté.
     */
    private val lows = HashMap<String, Array<ImageBitmap?>>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "golf-vues").apply { priority = Thread.MIN_PRIORITY + 1 } }
    @Volatile private var center = 0
    val lowVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private val pickMaps = LruCache<String, ByteArray>(8)
    private var fullCache: Triple<String, Int, ImageBitmap>? = null

    fun angles(state: String) = angles.optInt(state, angles.getInt("0"))

    /** Image la plus proche de l'angle (degrés). */
    fun index(state: String, rot: Float): Int {
        val n = angles(state)
        return (((rot % 360f + 360f) % 360f) / 360f * n).roundToInt() % n
    }

    /** État dessiné pour ces ouvrants : exact, sinon le plus petit qui les contient tous, sinon tout ouvert. */
    fun stateFor(mask: Int): String {
        val m = mask and 63
        masks.entries.firstOrNull { it.value == m }?.let { return it.key }
        return masks.entries.filter { it.value and m == m }.minByOrNull { Integer.bitCount(it.value) }?.key ?: "ALL"
    }

    private fun decode(state: String, elev: Int, i: Int, sample: Int): Bitmap? = runCatching {
        File(dir, "v${state}_${elev}_$i.webp").inputStream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Décode en arrière-plan toutes les vues légères (une seule fois). Libère les autres, sauf la voiture fermée. */
    fun warm(state: String, elev: Int) {
        val k = "${state}_$elev"
        synchronized(lows) {
            if (lows.containsKey(k)) return
            lows.keys.filter { it != "0_0" && it != k }.forEach { lows.remove(it) }
            lows[k] = arrayOfNulls(angles(state))
        }
        worker.execute {
            val arr = synchronized(lows) { lows[k] } ?: return@execute
            val n = arr.size; val t0 = SystemClock.elapsedRealtime()
            val done = BooleanArray(n); var ok = 0
            repeat(n) {
                // La plus proche de l'angle affiché d'abord (l'angle peut changer pendant le chargement)
                val c = center.coerceIn(0, n - 1)
                var i = -1
                for (d in 0..n / 2) { val a = (c + d) % n; val b = (c - d + n) % n; if (!done[a]) { i = a; break }; if (!done[b]) { i = b; break } }
                if (i < 0) return@repeat
                done[i] = true
                if (synchronized(lows) { lows[k] } !== arr) return@execute   // libéré entre-temps
                decode(state, elev, i, 2)?.let { b -> b.prepareToDraw(); arr[i] = b.asImageBitmap(); ok++ }
                if (ok % 6 == 0) lowVersion.intValue++
            }
            lowVersion.intValue++
            val ms = SystemClock.elapsedRealtime() - t0
            WakeLog.add(appCtx ?: return@execute, "Vue voiture : $ok/$n vues « $k » prêtes en ${ms / 100 / 10.0} s (${if (ok > 0) ms / ok else 0} ms par vue)")
        }
    }

    /** Vue légère de l'angle, sinon la plus proche déjà prête (jamais d'attente pendant la rotation). */
    fun low(state: String, elev: Int, i: Int): ImageBitmap? {
        val arr = synchronized(lows) { lows["${state}_$elev"] } ?: return null
        val n = arr.size
        for (d in 0..n / 2) { arr[(i + d) % n]?.let { if (d > 0) missed++; return it }; arr[(i - d + n) % n]?.let { missed++; return it } }
        return null
    }

    /** Vue en pleine définition (une seule gardée). */
    fun loadFull(state: String, elev: Int, i: Int): ImageBitmap? {
        val k = "${state}_$elev"
        fullCache?.let { (s, j, b) -> if (s == k && j == i) return b }
        val b = decode(state, elev, i, 1)?.also { it.prepareToDraw() }?.asImageBitmap() ?: return null
        fullCache = Triple(k, i, b)
        return b
    }

    // Mesure de la rotation, écrite au journal à chaque lâcher du doigt
    private var dragT0 = 0L; private var dragViews = 0; private var lastIdx = -1; @Volatile private var missed = 0
    fun startDrag() { dragT0 = SystemClock.elapsedRealtime(); dragViews = 0; missed = 0; lastIdx = -1 }
    fun shown(i: Int) { center = i; if (i != lastIdx) { dragViews++; lastIdx = i } }
    fun endDrag() {
        val s = (SystemClock.elapsedRealtime() - dragT0 - 150) / 1000.0
        if (dragViews >= 5) WakeLog.add(appCtx ?: return, "Vue voiture : rotation, $dragViews vues en ${"%.1f".format(s)} s, $missed fois une vue voisine (pas encore prête)")
    }

    /** Fiche de la pièce au point (fx, fy) de l'image (0..1), ou null. */
    fun pick(state: String, elev: Int, i: Int, fx: Float, fy: Float): Pair<String, String>? {
        if (fx !in 0f..1f || fy !in 0f..1f) return null
        val k = "${state}_${elev}_$i"
        val map = pickMaps.get(k) ?: runCatching {
            val b = File(dir, "p$k.png").inputStream().use { BitmapFactory.decodeStream(it) }
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
        @Volatile private var appCtx: Context? = null
        /** Change quand le pack arrive (l'écran voiture se met à jour). */
        val version = androidx.compose.runtime.mutableIntStateOf(0)
        fun get(c: Context): GolfViews? {
            inst?.let { return it }
            appCtx = c.applicationContext
            if (!GolfPack.ready(c)) return null
            return runCatching { GolfViews(GolfPack.dir(c), JSONObject(File(GolfPack.dir(c), "views.json").readText())) }
                .onFailure { WakeLog.add(c, "Vue voiture : pack illisible (${it.message})") }.getOrNull()?.also { inst = it }
        }
        fun reset() { inst = null; version.intValue++ }
        /** Au démarrage (en fond) : télécharge le pack s'il manque, sinon prépare la voiture fermée. */
        fun prewarm(c: Context) { val v = get(c); if (v == null) GolfPack.ensure(c) else v.warm("0", 0) }
    }
}
