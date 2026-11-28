package com.koala.koackpit

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fichiers envoyés depuis le S25 (Partager → Koackpit Téléphone). Récupérés dès que le téléphone en signale :
 * - un APK est installé directement (accès système), avec un message à l'écran ;
 * - tout autre fichier est rangé dans Téléchargements/Koackpit de l'autoradio.
 */
object Inbox {
    fun fetch(ctx: Context, base: String) {
        val app = ctx.applicationContext
        val list = runCatching { JSONArray(get("$base/outbox", 5000).toString(Charsets.UTF_8)) }.getOrNull() ?: return
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val id = o.getString("id"); val name = o.getString("name")
            val dir = File(app.cacheDir, "recus").apply { mkdirs() }
            val f = File(dir, name.replace('/', '_'))
            val size = o.optLong("size", -1)
            val main = Handler(Looper.getMainLooper())
            fun refresh() = main.post { InboxOverlay.tick(app) }
            InboxOverlay.show("Réception de $name", 0f); refresh()
            val got = runCatching {
                val c = URL("$base/outbox/get?id=" + URLEncoder.encode(id, "UTF-8")).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 4000; c.readTimeout = 30_000
                    c.inputStream.use { inp ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var n = 0L; var lastPct = -1
                            while (true) {
                                val r = inp.read(buf); if (r < 0) break
                                out.write(buf, 0, r); n += r
                                val pct = if (size > 0) (n * 100 / size).toInt() else 0
                                if (pct != lastPct) { lastPct = pct; InboxOverlay.show("Réception de $name · $pct %", pct / 100f); refresh() }
                            }
                        }
                    }
                } finally { c.disconnect() }
                f.length() == size
            }.getOrDefault(false)
            if (!got) {
                WakeLog.add(app, "Fichier du téléphone : $name — réception incomplète, nouvel essai plus tard")
                InboxOverlay.done("$name : réception coupée, nouvel essai bientôt", true); refresh()
                f.delete(); return
            }
            val apk = name.endsWith(".apk", true)
            if (apk) { InboxOverlay.show("Installation de $name…", null); refresh() }
            val msg = if (apk) install(app, f) else store(app, f)
            val ok = msg.contains("installée") || msg.startsWith("rangé")
            WakeLog.add(app, "Fichier du téléphone : $name → $msg")
            InboxOverlay.done(if (ok) "✓ $msg" else "$name : $msg", !ok); refresh()
            runCatching { get("$base/outbox/done?id=" + URLEncoder.encode(id, "UTF-8") + "&r=" + URLEncoder.encode(msg, "UTF-8"), 4000) }
            f.delete()
        }
    }

    private fun install(app: Context, f: File): String {
        val info = runCatching { app.packageManager.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull()
            ?: return "ce n'est pas une appli valide, rien installé"
        val pkg = info.packageName
        if (pkg == app.packageName) return "c'est Koackpit lui-même : passe par la mise à jour habituelle"
        val label = runCatching {
            info.applicationInfo!!.sourceDir = f.absolutePath; info.applicationInfo!!.publicSourceDir = f.absolutePath
            app.packageManager.getApplicationLabel(info.applicationInfo!!).toString()
        }.getOrDefault(pkg)
        val tmp = "/data/local/tmp/koackpit-recu.apk"
        val out = runCatching {
            RootAccess.run(app, "cp '${f.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp 2>&1; rm -f $tmp", 180_000)
        }.getOrElse { "ERREUR ${it.message}" }
        return if ("Success" in out) "« $label » installée" else "installation de « $label » refusée (${out.trim().takeLast(100)})"
    }

    /** Rangement par l'accès système (Android 9 : pas de dossier Téléchargements accessible autrement). */
    private fun store(app: Context, f: File): String {
        val dst = "/sdcard/Download/Koackpit/${f.name.replace("'", "_")}"
        val out = runCatching {
            RootAccess.run(app, "mkdir -p /sdcard/Download/Koackpit && cp '${f.absolutePath}' '$dst' && chmod 666 '$dst' && echo COPIE_OK 2>&1", 60_000)
        }.getOrElse { "ERREUR ${it.message}" }
        return if ("COPIE_OK" in out) "rangé dans Téléchargements/Koackpit" else "rangement impossible (${out.trim().takeLast(80)})"
    }

    private fun get(url: String, timeout: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try { c.connectTimeout = timeout; c.readTimeout = timeout; return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }
}
