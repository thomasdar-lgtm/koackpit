package com.koala.koackpit

import android.content.Context
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
 * (carte des pièces calculée avec chaque vue). Aucune 3D ni puce graphique sollicitée : fluide partout.
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
    val panel = remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(state) { panel.value = null }

    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        Crossfade(state, animationSpec = tween(350), label = "ouvrants") { st ->
            val idx = views.index(st, rot)
            val n = views.angles(st)
            // Dernière image affichée gardée tant que la suivante se charge (pas de clignotement en tournant)
            val shown = remember(st) { mutableStateOf(views.cached(st, idx) ?: views.load(st, idx)) }
            LaunchedEffect(st, idx) {
                (views.cached(st, idx) ?: withContext(Dispatchers.IO) { views.load(st, idx) })?.let { shown.value = it }
                withContext(Dispatchers.IO) { for (d in listOf(1, -1, 2, -2)) views.load(st, (idx + d + n) % n) }
            }
            val bmp = shown.value
            bmp?.let {
                Image(
                    it.asImageBitmap(), "Ma Golf", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                )
            }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, 1.6f)
                        rot -= pan.x / with(density) { 3.dp.toPx() }   // ~1° par 3 dp de doigt
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
private class GolfViews(private val app: Context, meta: JSONObject) {
    val w = meta.getInt("w"); val h = meta.getInt("h")
    private val pw = meta.getInt("pw"); private val ph = meta.getInt("ph")
    private val angles = meta.getJSONObject("angles")
    private val infos = meta.getJSONArray("infos")
    // ~12 vues gardées en mémoire (3,3 Mo chacune) : le défilement autour de l'angle courant reste instantané
    private val bitmaps = LruCache<String, Bitmap>(12)
    private val pickMaps = LruCache<String, ByteArray>(8)

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

    fun cached(state: String, i: Int): Bitmap? = bitmaps.get("$state/$i")

    fun load(state: String, i: Int): Bitmap? {
        val k = "$state/$i"
        bitmaps.get(k)?.let { return it }
        return runCatching { app.assets.open("golf/views/v${state}_$i.webp").use { BitmapFactory.decodeStream(it) } }
            .getOrNull()?.also { bitmaps.put(k, it) }
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
        fun get(c: Context): GolfViews? = inst ?: runCatching {
            GolfViews(c.applicationContext, JSONObject(c.assets.open("golf/views/views.json").bufferedReader().readText()))
        }.onFailure { WakeLog.add(c, "Vue voiture : fichiers absents (${it.message})") }.getOrNull()?.also { inst = it }
    }
}
