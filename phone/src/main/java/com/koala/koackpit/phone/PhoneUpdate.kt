package com.koala.koackpit.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mises à jour de Koackpit Téléphone (1.6), depuis la même release GitHub que Koackpit.
 *
 * La release contient KoackpitTelephone.apk et KoackpitTelephone.txt (« numéro version », ex. « 7 1.6 »).
 * L'appli compare au numéro installé ; si plus récent : bouton « Mettre à jour » dans l'appli, et notification
 * (vérification toutes les 6 h par le service). L'installation passe par l'installateur d'Android : une
 * confirmation à toucher, l'appli et ses autorisations sont conservées.
 */
object PhoneUpdate {
    private const val API = "https://api.github.com/repos/thomasdar-lgtm/koackpit/releases/latest"
    data class Info(val code: Int, val name: String, val apkUrl: String)

    fun installed(c: Context): Pair<Long, String> {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        return (if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()) to (p.versionName ?: "?")
    }

    private fun get(url: String, timeout: Int = 10_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout * 3
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** Version plus récente disponible, ou null (à jour). Lève une exception si GitHub est injoignable. */
    fun check(c: Context): Info? {
        val con = get(API)
        try {
            if (con.responseCode != 200) throw java.io.IOException("GitHub : ${con.responseCode}")
            val assets = JSONObject(con.inputStream.bufferedReader().readText()).getJSONArray("assets")
            var apk: String? = null; var txt: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) {
                    "KoackpitTelephone.apk" -> apk = a.getString("browser_download_url")
                    "KoackpitTelephone.txt" -> txt = a.getString("browser_download_url")
                }
            }
            if (apk == null || txt == null) return null
            val t = get(txt).let { h -> try { h.inputStream.bufferedReader().readText().trim() } finally { h.disconnect() } }
            val code = t.substringBefore(' ').toIntOrNull() ?: return null
            val name = t.substringAfter(' ', "?")
            return if (code > installed(c).first) Info(code, name, apk) else null
        } finally { con.disconnect() }
    }

    /** Télécharge l'APK et lance l'installateur d'Android (confirmation demandée à l'utilisateur). */
    fun install(c: Context, info: Info, progress: (Int) -> Unit) {
        val f = File(c.cacheDir, "KoackpitTelephone.apk")
        val con = get(info.apkUrl, 20_000)
        try {
            if (con.responseCode != 200) throw java.io.IOException("téléchargement : ${con.responseCode}")
            val total = con.contentLengthLong
            con.inputStream.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt()) }
            } }
        } finally { con.disconnect() }
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("KoackpitTelephone.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val back = Intent(c, MainActivity::class.java).setAction(ACTION_INSTALL).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val p = PendingIntent.getActivity(c, 1, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(p.intentSender)
        }
    }
    const val ACTION_INSTALL = "com.koala.koackpit.phone.INSTALL_STATUS"

    /** Vérification de fond (service) : notification une seule fois par nouvelle version. */
    fun backgroundCheck(c: Context) {
        val info = runCatching { check(c) }.getOrNull() ?: return
        val prefs = c.getSharedPreferences("update", Context.MODE_PRIVATE)
        if (prefs.getInt("notified", 0) >= info.code) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("maj", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7, Notification.Builder(c, "maj")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Koackpit Téléphone ${info.name} disponible")
            .setContentText("Touche pour mettre à jour")
            .setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putInt("notified", info.code).apply()
        RadioWatch.add("mise à jour ${info.name} disponible : notification envoyée")
    }
}
