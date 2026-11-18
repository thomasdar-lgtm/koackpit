package com.koala.koackpit

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Paroles synchronisées (LRCLIB, base ouverte et gratuite), calées sur la position exacte du morceau donnée
 * par l'appli Koackpit Téléphone. Gardées en cache sur l'autoradio (pas de nouveau téléchargement).
 */
object Lyrics {
    /** Une ligne : début en ms et texte. */
    data class Line(val at: Long, val text: String)

    private val mem = HashMap<String, List<Line>>()

    private fun key(artist: String, title: String) = "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    /** Paroles du morceau (vide : introuvables ; null : pas de réseau, à réessayer). Hors fil principal. */
    fun get(ctx: Context, artist: String, title: String, durationMs: Long): List<Line>? {
        val k = key(artist, title)
        mem[k]?.let { return it }
        val dir = File(ctx.cacheDir, "paroles").apply { mkdirs() }
        val f = File(dir, k.hashCode().toUInt().toString(16) + ".lrc")
        val lrc = if (f.exists()) f.readText() else {
            val found = runCatching { fetch(artist, title, durationMs) }.getOrNull() ?: return null   // réseau : on réessaiera
            f.writeText(found)
            found
        }
        return parse(lrc).also { mem[k] = it }
    }

    private fun http(url: String): String? = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 6000; readTimeout = 8000
        setRequestProperty("User-Agent", "Koackpit/${BuildConfig.VERSION_NAME} (autoradio)")
        try { if (responseCode == 200) inputStream.bufferedReader().readText() else if (responseCode == 404) "" else null } finally { disconnect() }
    }

    /** Texte LRC, « » si introuvable ; exception si le réseau ne répond pas. */
    private fun fetch(artist: String, title: String, durationMs: Long): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var url = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}"
        if (durationMs > 0) url += "&duration=${durationMs / 1000}"
        val one = http(url) ?: throw java.io.IOException("lrclib")
        if (one.isNotEmpty()) JSONObject(one).optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        // Pas trouvé tel quel : recherche plus large, on garde le résultat de durée la plus proche
        val list = http("https://lrclib.net/api/search?q=${enc("$artist $title")}") ?: throw java.io.IOException("lrclib")
        if (list.isEmpty()) return ""
        val arr = JSONArray(list)
        var best = ""; var bestGap = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val gap = if (durationMs > 0) kotlin.math.abs((o.optDouble("duration", 0.0) * 1000).toLong() - durationMs) else 0L
            if (gap < bestGap) { bestGap = gap; best = synced }
        }
        return if (bestGap <= 5000 || durationMs <= 0) best else ""
    }

    /** « [01:23.45] texte » → lignes triées. */
    fun parse(lrc: String): List<Line> {
        val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
        val out = ArrayList<Line>()
        for (raw in lrc.lines()) {
            val stamps = re.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                val ms = mm.toLong() * 60_000 + ss.toLong() * 1000 + when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out.add(Line(ms, text))
            }
        }
        return out.sortedBy { it.at }
    }

    /** Index de la ligne en cours à [pos] (-1 avant la première). */
    fun indexAt(lines: List<Line>, pos: Long): Int = lines.indexOfLast { it.at <= pos }
}

/**
 * Paroles du morceau en cours : la ligne chantée en grand et en jaune, les voisines plus petites.
 * [before]/[after] : nombre de lignes affichées autour. Rien si l'appli du téléphone n'est pas reliée
 * ou si les paroles sont introuvables.
 */
@Composable
fun LyricsView(title: String, artist: String, btPlaying: Boolean, before: Int, after: Int, big: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val connected = PhoneLink.connected.value
    val dur = PhoneLink.duration.value
    var lines by remember(title, artist) { mutableStateOf<List<Lyrics.Line>?>(null) }
    var pos by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(title, artist, connected) {
        if (!connected || title.isBlank()) { lines = null; return@LaunchedEffect }
        // Téléchargement (réessai toutes les 20 s si le réseau est coupé)
        while (true) {
            val l = withContext(Dispatchers.IO) { runCatching { Lyrics.get(ctx.applicationContext, artist, title, dur) }.getOrDefault(emptyList()) }
            lines = l
            if (l != null) break
            delay(20_000)
        }
    }
    LaunchedEffect(title, connected, btPlaying) {
        if (!connected) { pos = null; return@LaunchedEffect }
        while (true) { pos = if (PhoneLink.matches(title)) PhoneLink.positionMs(btPlaying) else null; delay(200) }
    }
    val ls = lines
    val p = pos
    if (ls.isNullOrEmpty() || p == null) return
    LyricsLines(ls, Lyrics.indexAt(ls, p + 300), before, after, big, modifier)   // +0,3 s : la ligne s'allume au moment où elle est chantée
}

/** Affichage seul (séparé pour les captures d'écran). */
@Composable
fun LyricsLines(lines: List<Lyrics.Line>, cur: Int, before: Int, after: Int, big: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 4.dp)) {
        for (i in (cur - before)..(cur + after)) {
            val text = when {
                i < 0 -> if (i == cur) "♪" else ""
                i >= lines.size -> ""
                else -> lines[i].text.ifBlank { "♪" }
            }
            val now = i == cur
            val color by animateColorAsState(if (now) Yellow else Color.White.copy(alpha = if (kotlin.math.abs(i - cur) == 1) 0.55f else 0.3f), label = "ligne")
            Text(
                text, color = color, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontSize = if (now) (if (big) 24.sp else 16.sp) else (if (big) 17.sp else 13.sp),
                fontWeight = if (now) FontWeight.Black else FontWeight.Bold,
                lineHeight = if (now) (if (big) 28.sp else 19.sp) else (if (big) 20.sp else 15.sp),
            )
        }
    }
}
