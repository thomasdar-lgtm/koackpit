package com.koala.koackpit

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pochettes d'album sans appli sur le téléphone : le Bluetooth donne le titre et l'artiste, et Koackpit
 * demande la pochette au catalogue public de Deezer (internet via le partage de connexion du téléphone).
 * Pochettes gardées sur l'autoradio (dossier cache) : un morceau déjà vu s'affiche sans internet.
 */
object CoverArt {
    private val mem = object : LinkedHashMap<String, ImageBitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 30
    }

    @Volatile private var loggedOk = false
    @Volatile private var loggedFail = false

    private fun key(title: String, artist: String) = "${artist.trim().lowercase()}|${title.trim().lowercase()}"

    private fun get(url: String): ByteArray? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 4000; readTimeout = 6000
            try { if (responseCode == 200) inputStream.readBytes() else null } finally { disconnect() }
        }
    }.getOrNull()

    /** Titre nettoyé pour la recherche : sans « (Remastered…) », « - Live », « feat. »… */
    private fun clean(s: String) = s.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "")
        .replace(Regex("(?i)\\s+(feat\\.?|ft\\.?)\\s+.*$"), "").trim()

    private fun search(q: String): String? {
        val js = get("https://api.deezer.com/search?limit=1&q=" + URLEncoder.encode(q, "UTF-8"))?.let { JSONObject(String(it)) } ?: return null
        return js.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("album")?.optString("cover_big")?.takeIf { it.startsWith("http") }
    }

    /** Pochette du morceau (appel réseau : jamais sur le fil principal). null si introuvable. */
    fun fetch(ctx: Context, title: String, artist: String): ImageBitmap? {
        if (title.isBlank()) return null
        val k = key(title, artist)
        synchronized(mem) { if (mem.containsKey(k)) return mem[k] }
        val file = File(ctx.cacheDir, "pochettes/${k.hashCode().toUInt().toString(16)}.jpg")
        val bytes = if (file.exists()) file.readBytes() else {
            val t = clean(title); val a = clean(artist)
            val url = (if (a.isNotEmpty()) search("artist:\"$a\" track:\"$t\"") else null) ?: search("$a $t".trim())
            url?.let { get(it) }?.also { b -> runCatching { file.parentFile?.mkdirs(); file.writeBytes(b) } }
        }
        val bmp = bytes?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
        // Journal : premier succès, et premier échec (pour le diagnostic)
        if (bmp != null && !loggedOk) { loggedOk = true; WakeLog.add(ctx, "Pochette trouvée (Deezer) : $title") }
        if (bmp == null && !loggedFail) { loggedFail = true; WakeLog.add(ctx, "Pochette introuvable : $artist – $title" + if (bytes == null) " (internet ?)" else "") }
        // On ne mémorise un échec que si internet a répondu (sinon on réessaiera au prochain affichage)
        if (bmp != null || bytes != null) synchronized(mem) { mem[k] = bmp }
        return bmp
    }
}
