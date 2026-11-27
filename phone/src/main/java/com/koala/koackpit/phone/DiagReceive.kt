package com.koala.koackpit.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fichier de diagnostic envoyé par Koackpit (autoradio) : rangé dans Téléchargements/Koackpit, puis une
 * notification « Diagnostic Koackpit » ouvre directement le menu Partager (pour l'envoyer à Claude).
 * Remplace le QR code, que Chrome n'arrivait pas à télécharger.
 */
object DiagReceive {
    fun save(c: Context, ins: InputStream, len: Long): Boolean = runCatching {
        val name = "koackpit-diag-" + SimpleDateFormat("dd-MM_HH'h'mm", Locale.FRANCE).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Koackpit")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = c.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        var left = len
        cr.openOutputStream(uri)!!.use { o ->
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                o.write(buf, 0, n); left -= n
            }
        }
        if (left > 0) { cr.delete(uri, null, null); return false }
        cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, "Envoyer le diagnostic").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(c, 3, chooser, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("diag", "Diagnostic Koackpit", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(9, Notification.Builder(c, "diag")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Diagnostic Koackpit")
            .setContentText("Reçu de l'autoradio (${len / 1_048_576} Mo) — touche pour l'envoyer")
            .setContentIntent(pi).setAutoCancel(true).build())
        RadioWatch.add("Diagnostic reçu de l'autoradio : $name")
        true
    }.getOrDefault(false)
}
