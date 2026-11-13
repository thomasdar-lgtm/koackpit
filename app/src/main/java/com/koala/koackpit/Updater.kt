package com.koala.koackpit

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String)

class UpdateState {
    var info by mutableStateOf<UpdateInfo?>(null)
    var progress by mutableStateOf<Float?>(null)
    var status by mutableStateOf("")
}

object Updater {
    private const val API = "https://api.github.com/repos/thomasdar-lgtm/koackpit/releases/latest"

    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Renvoie la nouvelle version si elle existe, null si à jour. Lève une exception si hors ligne. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val v = o.getString("tag_name").removePrefix("v")
            val assets = o.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "Koackpit.apk") url = a.getString("browser_download_url")
            }
            if (url != null && newer(v, BuildConfig.VERSION_NAME)) UpdateInfo(v, url) else null
        } finally {
            c.disconnect()
        }
    }

    /** Téléchargement avec 3 essais (Wi-Fi de l'autoradio instable). Lève l'erreur du dernier essai. */
    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        var last: Exception? = null
        repeat(3) { i ->
            try { return downloadOnce(ctx, url, onProgress) } catch (e: Exception) {
                last = e
                kotlinx.coroutines.delay(2000L * (i + 1))
            }
        }
        throw last ?: IllegalStateException("échec")
    }

    private suspend fun downloadOnce(ctx: Context, url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "Koackpit.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            f
        } finally {
            c.disconnect()
        }
    }

    private var lastCheck = 0L

    /**
     * Recherche une mise à jour et remplit le bandeau. Renvoie true si une version plus récente existe,
     * false si Koackpit est à jour, null si GitHub est injoignable.
     */
    suspend fun refresh(upd: UpdateState, force: Boolean): Boolean? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < 20_000) return upd.info != null
        if (upd.progress != null) return true   // téléchargement en cours : on ne touche à rien
        return runCatching { check() }.fold(
            onSuccess = { lastCheck = now; upd.info = it; it != null },
            onFailure = { null },
        )
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, file: File) {
        // L'installateur ne doit jamais s'ouvrir dans l'écran partagé (il y resterait bloqué)
        val wasOpen = MusicActivity.isOpen()
        MusicActivity.closeIfOpen()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val go = Runnable {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // Laisse le temps à Android de refermer l'écran partagé
        if (wasOpen) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(go, 1000) else go.run()
    }
}

@Composable
fun UpdateBanner(upd: UpdateState, modifier: Modifier = Modifier) {
    val info = upd.info ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    UpdateChip(modifier, info.version, upd.progress, upd.status) {
        upd.status = ""
        upd.progress = 0f
        scope.launch {
            val d = runCatching { Updater.download(ctx, info.url) { upd.progress = it } }
            upd.progress = null
            val f = d.getOrElse { e ->
                // Message précis pour comprendre l'échec (réseau, certificat, délai…)
                upd.status = "Échec : ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}"
                return@launch
            }
            // Appli compagnon présente : elle affiche l'écran de mise à jour et installe
            if (UpdaterApp.handOff(ctx, f)) return@launch
            upd.status = "Installation…"
            val r = withContext(Dispatchers.IO) {
                // Installation silencieuse via l'accès système : jamais d'écran d'installation Android
                runCatching { RootAccess.silentInstall(ctx, f) { st -> upd.status = "Installation : $st" } }
            }
            r.exceptionOrNull()?.let { upd.status = "Échec : ${it.message ?: it.javaClass.simpleName}" }
        }
    }
}

/** Petite carte « mise à jour » (en bas à droite de l'accueil) : symbole, version, bouton Installer. */
@Composable
fun UpdateChip(modifier: Modifier, version: String, progress: Float?, status: String, onInstall: () -> Unit) {
    Column(
        modifier.width(176.dp).clip(RoundedCornerShape(18.dp)).background(CardHi)
            .border(1.5.dp, Yellow.copy(alpha = 0.7f), RoundedCornerShape(18.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Yellow), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Refresh, null, tint = Navy, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Mise à jour", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(version, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
        }
        when {
            progress != null -> {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Yellow, trackColor = Navy)
                Text("Téléchargement ${(progress * 100).toInt()} %", color = Color.White, fontSize = 11.sp)
            }
            status.startsWith("Installation") -> Text(status, color = Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            else -> {
                if (status.startsWith("Échec")) Text(status, color = Red, fontSize = 11.sp, maxLines = 3)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Yellow).clickable(onClick = onInstall)
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (status.startsWith("Échec")) "Réessayer" else "Installer", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}
