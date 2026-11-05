package com.koala.koackpit

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.lang.reflect.Method

/**
 * Test d'écran partagé (Android 10) : GPS à gauche, musique à droite.
 * Utilise des API système cachées : le résultat dépend de l'autoradio.
 */
object SplitScreen {
    private const val SPLIT_PRIMARY = 3          // WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
    private const val CREATE_MODE_LEFT = 0       // SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT

    /** Accès aux méthodes cachées (contournement valable jusqu'à Android 10). */
    private fun hidden(cls: Class<*>, name: String, vararg types: Class<*>): Method {
        val gdm = Class::class.java.getMethod(
            "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass
        )
        return (gdm.invoke(cls, name, types) as Method).apply { isAccessible = true }
    }

    /** Infos système utiles pour comprendre le résultat. */
    fun systemInfo(ctx: Context): String {
        val pm = ctx.packageManager
        val cr = ctx.contentResolver
        fun g(k: String) = runCatching { Settings.Global.getString(cr, k) }.getOrNull() ?: "-"
        return listOf(
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Multi-fenêtre système : " + (if (pm.hasSystemFeature("android.software.picture_in_picture")) "PiP oui" else "PiP non") +
                ", fenêtres libres " + (if (pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) "oui" else "non"),
            "force_resizable_activities = ${g("force_resizable_activities")}",
            "enable_freeform_support = ${g("enable_freeform_support")}",
        ).joinToString("\n")
    }

    /** Lance le GPS en écran partagé puis la musique à côté. Renvoie le compte rendu. */
    fun launch(ctx: Context, navPkgs: List<String>, side: Intent, done: (String) -> Unit) {
        val log = StringBuilder()
        val pm = ctx.packageManager
        val nav = navPkgs.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        val music = side
        if (nav == null) { done("GPS introuvable"); return }

        val opts = ActivityOptions.makeBasic()
        runCatching {
            hidden(ActivityOptions::class.java, "setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
                .invoke(opts, SPLIT_PRIMARY)
            log.append("1. Mode écran partagé demandé : OK\n")
        }.onFailure { log.append("1. Mode écran partagé refusé : ${it.cause ?: it}\n") }
        runCatching {
            hidden(ActivityOptions::class.java, "setSplitScreenCreateMode", Int::class.javaPrimitiveType!!)
                .invoke(opts, CREATE_MODE_LEFT)
            log.append("2. GPS à gauche : OK\n")
        }.onFailure { log.append("2. Position gauche refusée : ${it.cause ?: it}\n") }

        runCatching {
            ctx.startActivity(nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle())
            log.append("3. GPS lancé\n")
        }.onFailure {
            log.append("3. Lancement GPS échoué : $it\n")
            done(log.toString()); return
        }

        // La musique s'ouvre dans l'autre moitié, après l'ouverture du GPS
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                ctx.startActivity(
                    music.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                )
                log.append("4. Lecteur Bluetooth lancé à côté\n")
            }.onFailure { log.append("4. Lancement musique échoué : $it\n") }
            done(log.toString())
        }, 1500)
    }
}
