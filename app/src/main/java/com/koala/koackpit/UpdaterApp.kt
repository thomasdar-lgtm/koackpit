package com.koala.koackpit

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Appli compagnon « Koackpit Mise à jour » (com.koala.koackpit.updater). Étant une appli séparée, elle reste
 * à l'écran pendant que Koackpit est remplacé, puis le rouvre : la mise à jour se fait sans écran parasite.
 * Koackpit l'installe lui-même, en silence, depuis la release GitHub (fichier KoackpitUpdater.bin).
 */
object UpdaterApp {
    private const val PKG = "com.koala.koackpit.updater"
    private const val ACTIVITY = "$PKG.UpdateActivity"
    private const val MIN_VERSION = 1L
    private const val ASSET = "KoackpitUpdater.bin"
    private const val API = "https://api.github.com/repos/thomasdar-lgtm/koackpit/releases/latest"
    const val HANDOFF_MARKER = "updater-handoff"

    private fun installedVersion(ctx: Context): Long? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(PKG, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    fun ready(ctx: Context) = (installedVersion(ctx) ?: 0L) >= MIN_VERSION

    /** Confie l'installation à l'appli compagnon. Renvoie false si elle n'est pas disponible. */
    fun handOff(ctx: Context, apk: File): Boolean {
        if (!ready(ctx)) return false
        return runCatching {
            File(ctx.filesDir, HANDOFF_MARKER).writeText("1")
            ctx.startActivity(
                Intent().setClassName(PKG, ACTIVITY).putExtra("apk", apk.absolutePath)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { File(ctx.filesDir, HANDOFF_MARKER).delete() }.isSuccess
    }

    /** Installe ou met à jour l'appli compagnon si besoin (en arrière-plan, sans écran). */
    fun ensureInstalled(ctx: Context) {
        if (ready(ctx)) return
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            val assets = try { JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("assets") } finally { c.disconnect() }
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == ASSET) url = a.getString("browser_download_url")
            }
            if (url == null) return
            val f = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "KoackpitUpdater.apk")
            val d = URL(url).openConnection() as HttpURLConnection
            d.connectTimeout = 20000; d.readTimeout = 60000
            try { d.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { d.disconnect() }
            val tmp = "/data/local/tmp/koackpit-updater.apk"
            RootAccess.run(ctx, "cp '${f.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp 2>&1; rm -f $tmp", 180_000)
            f.delete()
        }
    }
}
