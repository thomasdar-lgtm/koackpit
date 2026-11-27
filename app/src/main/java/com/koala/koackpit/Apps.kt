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
    /** Au démarrage de la voiture : lancer la conduite (GPS + lecteur). Désactivé par défaut : accueil Koackpit. */
    fun autoDrive(ctx: Context) = sp(ctx).getBoolean("autoDrive2", false)
    fun setAutoDrive(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("autoDrive2", on).apply()
    /** Barre d'appel de l'autoradio coupée pendant les appels (la fiche Koackpit la remplace). Activé par défaut. */
    fun hideVendorCall(ctx: Context) = sp(ctx).getBoolean("hideVendorCall", true)
    fun setHideVendorCall(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("hideVendorCall", on).apply()
    fun splitLog(ctx: Context): String = sp(ctx).getString("splitLog", "") ?: ""
    fun setSplitLog(ctx: Context, s: String) = sp(ctx).edit().putString("splitLog", s).apply()
}

object Apps {
    fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun firstInstalled(ctx: Context, pkgs: List<String>) = pkgs.firstOrNull { installed(ctx, it) }

    /** Ouvre la première appli installée de la liste. Renvoie false si aucune. */
    /** Dernière appli ouverte à la main depuis Koackpit (la surveillance du démarrage ne la referme pas). */
    fun userOpenAt(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getLong("ouverture", 0L)
    /** À appeler quand Thomas ouvre lui-même un autre écran (caméra, partage…) : la surveillance le laisse faire. */
    fun markUserOpen(ctx: Context) = mark(ctx)
    private fun mark(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putLong("ouverture", System.currentTimeMillis()).apply()

    fun open(ctx: Context, pkgs: List<String>): Boolean {
        mark(ctx)
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
        mark(ctx)
        ctx.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }.isSuccess

    /**
     * Bureau d'origine de l'autoradio : ce n'est pas une appli « lançable », il n'apparaissait donc pas dans le
     * tiroir. Ajouté sous le nom « Bureau autoradio » (accès aux réglages et aux applis du fabricant).
     */
    private fun desktop(ctx: Context, pm: PackageManager, size: Int): List<LaunchApp> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "com.android.settings" }
            .map { ri ->
                val icon = runCatching { ri.loadIcon(pm).toBitmap(size, size) }.getOrNull()
                LaunchApp(ri.activityInfo.packageName, ri.activityInfo.name, "Bureau autoradio", icon)
            }

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
            .plus(desktop(ctx, pm, size))
            .distinctBy { it.pkg + "/" + it.activity }
            .sortedBy { it.label.lowercase() }
    }
}
