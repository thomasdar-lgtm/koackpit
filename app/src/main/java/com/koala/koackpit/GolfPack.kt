package com.koala.koackpit

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Pack d'images de la Golf, téléchargé à part (0.20.0).
 *
 * Les vues pré-calculées (72 angles × 12 états × 2 hauteurs ≈ 36 Mo) ne sont plus dans l'appli : chaque mise à jour
 * resterait sinon à ~45 Mo sur le forfait du téléphone. Le pack est publié dans la release GitHub « vues-<PACK> »
 * (workflow Render views), téléchargé UNE fois (reprise là où il s'était arrêté si la connexion coupe), décompressé
 * dans files/golf-vues-<PACK>. On ne change [PACK] que quand les images changent.
 */
object GolfPack {
    const val PACK = 1
    private const val URL_ZIP = "https://github.com/thomasdar-lgtm/koackpit/releases/download/vues-$PACK/golf-vues.zip"

    /** -1 = rien en cours ; 0..100 = téléchargement ; 101 = décompression. */
    val progress = mutableIntStateOf(-1)
    /** Dernier souci (affiché sous la voiture), ou null. */
    val problem = mutableStateOf<String?>(null)
    @Volatile private var running = false

    fun dir(c: Context) = File(c.filesDir, "golf-vues-$PACK")
    fun ready(c: Context) = File(dir(c), "views.json").exists()

    /** Lance le téléchargement si le pack manque (sans effet s'il est là ou déjà en cours). */
    fun ensure(c: Context) {
        val app = c.applicationContext
        if (ready(app) || running) return
        running = true
        Thread({
            try { fetch(app) } catch (e: Exception) {
                problem.value = "téléchargement interrompu, reprise à la prochaine connexion"
                WakeLog.add(app, "Vue voiture : pack d'images, échec (${e.javaClass.simpleName}: ${e.message?.take(80)})")
            } finally { running = false; progress.intValue = -1 }
        }, "golf-pack").start()
    }

    private fun fetch(app: Context) {
        val part = File(app.cacheDir, "golf-vues-$PACK.zip.part")
        var have = if (part.exists()) part.length() else 0L
        val t0 = SystemClock.elapsedRealtime()
        WakeLog.add(app, "Vue voiture : pack d'images n°$PACK, téléchargement" + if (have > 0) " (reprise à ${have / 1_048_576} Mo)" else "")
        problem.value = null; progress.intValue = 0
        var url = URL(URL_ZIP)
        var con: HttpURLConnection
        // GitHub renvoie vers son serveur de fichiers : on suit les renvois nous-mêmes (en gardant la reprise)
        var hops = 0
        while (true) {
            con = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = con.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, con.getHeaderField("Location")); con.disconnect(); continue }
            if (code == 416) { have = 0; part.delete(); con.disconnect(); continue }   // fichier changé : on repart de zéro
            if (code != 200 && code != 206) throw java.io.IOException("réponse $code")
            if (code == 200) have = 0                                              // pas de reprise possible
            break
        }
        val total = have + con.contentLengthLong.coerceAtLeast(0)
        RandomAccessFile(part, "rw").use { out ->
            out.setLength(have); out.seek(have)
            con.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024); var n: Int; var done = have
                while (inp.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress.intValue = (done * 100 / total).toInt().coerceIn(0, 100)
                }
            }
        }
        con.disconnect()
        // Décompression dans un dossier à part, renommé à la fin (jamais de pack à moitié prêt)
        progress.intValue = 101
        val tmp = File(app.filesDir, "golf-vues-$PACK.tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        var files = 0
        ZipInputStream(part.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = File(e.name).name
                if (e.isDirectory || name.isEmpty()) continue
                File(tmp, name).outputStream().use { z.copyTo(it) }; files++
            }
        }
        if (!File(tmp, "views.json").exists()) { part.delete(); throw java.io.IOException("pack incomplet ($files fichiers)") }
        val d = dir(app); d.deleteRecursively()
        if (!tmp.renameTo(d)) throw java.io.IOException("renommage impossible")
        part.delete()
        // Anciens packs : supprimés
        app.filesDir.listFiles()?.filter { it.name.startsWith("golf-vues-") && it.name != d.name }?.forEach { it.deleteRecursively() }
        GolfViews.reset()
        val s = (SystemClock.elapsedRealtime() - t0) / 1000
        WakeLog.add(app, "Vue voiture : pack d'images n°$PACK prêt ($files fichiers, ${total / 1_048_576} Mo, $s s)")
    }
}
