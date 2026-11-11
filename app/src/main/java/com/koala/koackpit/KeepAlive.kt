package com.koala.koackpit

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pourquoi Koackpit ne revenait pas au démarrage : à la mise en veille, le service « car » du fabricant
 * force l'arrêt de toutes les applis installées, sauf une liste figée et l'appli du fond d'écran animé.
 * Une appli arrêtée de force ne reçoit plus aucun signal, donc pas celui du réveil.
 * Koackpit fournit donc un fond d'écran animé (simple fond bleu nuit, aucun calcul) : choisi comme fond
 * d'écran, il protège Koackpit de cet arrêt.
 */
class KoackpitWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine {
        WakeService.start(this)
        return object : Engine() {
            override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) = paint(holder)
            override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = paint(holder)
            private fun paint(holder: SurfaceHolder) {
                runCatching {
                    val c = holder.lockCanvas() ?: return
                    c.drawColor(Color.rgb(0x00, 0x07, 0x1A))
                    holder.unlockCanvasAndPost(c)
                }
            }
        }
    }
}

object KeepAlive {
    fun active(ctx: Context): Boolean = runCatching {
        WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
    }.getOrDefault(false)

    /** Ouvre l'aperçu système du fond d'écran Koackpit : il suffit d'appuyer sur « Définir ». */
    fun request(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, KoackpitWallpaper::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "Sélecteur de fond d'écran indisponible") }
    }
}

/** Journal des démarrages (affiché dans l'outil de diagnostic), pour vérifier au lieu de supposer. */
object WakeLog {
    private const val FILE = "wake-log.txt"
    fun add(ctx: Context, msg: String) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            val lines = (if (f.exists()) f.readLines() else emptyList()).takeLast(40)
            val t = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
            f.writeText((lines + "$t  $msg").joinToString("\n"))
        }
    }
    fun read(ctx: Context): String = runCatching { File(ctx.filesDir, FILE).readText() }.getOrNull() ?: "vide"
}
