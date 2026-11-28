package com.koala.koackpit.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * Fichiers à envoyer à l'autoradio (1.9). Sur le S25 : Partager → « Koackpit Téléphone ». Le fichier attend ici
 * jusqu'à ce que Koackpit (autoradio) vienne le chercher ; un APK y est installé directement.
 */
object Outbox {
    private fun dir(c: Context) = File(c.filesDir, "envoi").apply { mkdirs() }

    /** Fichiers en attente : (identifiant, nom, taille). Identifiant = nom du fichier stocké. */
    fun list(c: Context): List<Triple<String, String, Long>> = dir(c).listFiles().orEmpty()
        .filter { it.isFile && '_' in it.name }.sortedBy { it.name }
        .map { Triple(it.name, it.name.substringAfter('_'), it.length()) }

    fun file(c: Context, id: String): File? = File(dir(c), id).takeIf { it.isFile && it.parentFile == dir(c) && !id.contains('/') }

    /** L'autoradio a récupéré le fichier ; [result] = ce qu'il en a fait (installée, rangé, refusée…). */
    fun done(c: Context, id: String, result: String) {
        val f = file(c, id) ?: return
        val name = id.substringAfter('_')
        RadioWatch.add("Envoi à l'autoradio : $name récupéré → $result")
        f.delete()
        runCatching {
            val nm = c.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("envoi", "Envois à l'autoradio", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id.hashCode(), android.app.Notification.Builder(c, "envoi")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Reçu par l'autoradio : $name")
                .setContentText(result.ifBlank { "fichier récupéré" })
                .setStyle(android.app.Notification.BigTextStyle().bigText(result.ifBlank { "fichier récupéré" }))
                .setAutoCancel(true).build())
        }
    }

    /** L'autoradio a commencé à le télécharger (notification mise à jour ensuite par [done]). */
    fun started(c: Context, id: String) = runCatching {
        val nm = c.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("envoi", "Envois à l'autoradio", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(id.hashCode(), android.app.Notification.Builder(c, "envoi")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Envoi à l'autoradio : ${id.substringAfter('_')}")
            .setContentText("L'autoradio le récupère…")
            .setOnlyAlertOnce(true).build())
    }

    fun add(c: Context, uri: Uri): String? = runCatching {
        var name = "fichier"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
        }
        name = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(80)
        val f = File(dir(c), "${System.currentTimeMillis()}_$name")
        c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        RadioWatch.add("Envoi à l'autoradio : $name en attente (${f.length() / 1024} Ko)")
        name
    }.getOrNull()
}

/** Cible du menu Partager : met le ou les fichiers en attente pour l'autoradio, puis se ferme. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        val app = applicationContext
        Thread {
            val names = uris.mapNotNull { Outbox.add(app, it) }
            runOnUiThread {
                Toast.makeText(app,
                    if (names.isEmpty()) "Rien à envoyer à l'autoradio"
                    else "Pour l'autoradio : ${names.joinToString()}\nIl partira dès que l'autoradio sera connecté.",
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
