package com.koala.koackpit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

data class LaunchApp(val pkg: String, val activity: String, val label: String, val icon: Bitmap?)

/** Applis ouvertes par les tuiles : la première installée de chaque liste est utilisée. */
object Targets {
    const val WAZE = "com.waze"
    const val MAPS = "com.google.android.apps.maps"
    val MUSIC = listOf("deezer.android.app", "android.car.app.media")
    val CAR_INFO = listOf("android.car.app.canbus")
    val OBD = listOf("com.ovz.carscanner", "org.prowl.torque")
    val PHONE = listOf("android.car.app.bt")
    val RADIO = listOf("android.car.app.radio")
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("koackpit", Context.MODE_PRIVATE)
    fun nav(ctx: Context): String = sp(ctx).getString("nav", Targets.WAZE) ?: Targets.WAZE
    fun setNav(ctx: Context, pkg: String) = sp(ctx).edit().putString("nav", pkg).apply()
    /** Mode conduite actif : à rétablir au réveil de l'autoradio s'il a été fermé par la veille. */
    fun driving(ctx: Context) = sp(ctx).getBoolean("driving", false)
    fun setDriving(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("driving", on).apply()
    /** Au démarrage de la voiture : lancer la conduite (GPS + lecteur côte à côte). Activé par défaut. */
    fun autoDrive(ctx: Context) = sp(ctx).getBoolean("autoDrive", true)
    fun setAutoDrive(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("autoDrive", on).apply()
    fun splitLog(ctx: Context): String = sp(ctx).getString("splitLog", "") ?: ""
    fun setSplitLog(ctx: Context, s: String) = sp(ctx).edit().putString("splitLog", s).apply()
}

object Apps {
    fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun firstInstalled(ctx: Context, pkgs: List<String>) = pkgs.firstOrNull { installed(ctx, it) }

    /** Ouvre la première appli installée de la liste. Renvoie false si aucune. */
    fun open(ctx: Context, pkgs: List<String>): Boolean {
        for (p in pkgs) {
            val i = ctx.packageManager.getLaunchIntentForPackage(p) ?: continue
            runCatching {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                return true
            }
        }
        return false
    }

    fun open(ctx: Context, a: LaunchApp) = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }.isSuccess

    /** Toutes les applis lançables, triées par nom (pour le tiroir). */
    fun list(ctx: Context): List<LaunchApp> {
        val pm = ctx.packageManager
        val size = (56 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        ).filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).toBitmap(size, size) }.getOrNull()
                LaunchApp(ri.activityInfo.packageName, ri.activityInfo.name, ri.loadLabel(pm).toString(), icon)
            }
            .distinctBy { it.pkg + "/" + it.activity }
            .sortedBy { it.label.lowercase() }
    }
}
