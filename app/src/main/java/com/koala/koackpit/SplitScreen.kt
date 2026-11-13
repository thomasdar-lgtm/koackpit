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

    private const val CREATE_MODE_LEFT = 0       // SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT
    private val BT_MAIN = android.content.ComponentName("android.car.app.bt", "android.car.app.bt.BtMain")

    /**
     * Mode conduite. Constat sur cet autoradio (Android 9 en interne) : la fenêtre principale de l'écran
     * partagé se place toujours à GAUCHE, et c'est elle seule dont on peut régler la largeur.
     * Le GPS est donc la fenêtre principale (gauche, 2/3) et le panneau Koackpit se met à côté (droite, 1/3).
     */
    fun launch(ctx: Context, navPkgs: List<String>, navIntent: Intent? = null, done: (String) -> Unit) {
        val log = StringBuilder()
        // navIntent : ouvrir le GPS directement sur une destination (ex. favori Maison de Waze)
        val nav = navIntent ?: navPkgs.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (nav == null) { done("GPS introuvable"); return }
        if (MusicActivity.isOpen()) { done("Conduite déjà ouverte"); return }
        val h = Handler(Looper.getMainLooper())

        // 1. Source audio Bluetooth. L'autoradio la mémorise : on ne passe par sa page BtMain
        //    (seul moyen de basculer l'ampli) que si une autre source est active.
        val src = CarLink.currentSource()
        val needBt = src != null && src != CarLink.SRC_BT
        if (needBt) runCatching {
            ctx.startActivity(Intent().setComponent(BT_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log.append("1. Source ${CarLink.sourceName(src)} → Bluetooth\n")
        }.onFailure { log.append("1. BtMain refusée : $it\n") }
        else log.append("1. Source déjà ${CarLink.sourceName(src)}\n")

        // 2. GPS en fenêtre principale, à gauche
        h.postDelayed({
            val opts = ActivityOptions.makeBasic()
            runCatching {
                hidden(ActivityOptions::class.java, "setLaunchWindowingMode", Int::class.javaPrimitiveType!!).invoke(opts, SPLIT_PRIMARY)
                hidden(ActivityOptions::class.java, "setSplitScreenCreateMode", Int::class.javaPrimitiveType!!).invoke(opts, CREATE_MODE_LEFT)
                log.append("2. Écran partagé demandé\n")
            }.onFailure { log.append("2. Écran partagé refusé : ${it.cause ?: it}\n") }
            runCatching {
                ctx.startActivity(nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts.toBundle())
                log.append("3. GPS lancé à gauche\n")
            }.onFailure { log.append("3. GPS échoué : $it\n"); done(log.toString()); return@postDelayed }

            // 3. Panneau Koackpit dans l'autre partie, à droite
            h.postDelayed({
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, MusicActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    )
                    log.append("4. Panneau lancé à droite\n")
                    Prefs.setDriving(ctx, true)
                    arrange(ctx)
                }.onFailure { log.append("4. Panneau échoué : $it\n") }
                done(log.toString())
            }, 800)
        }, if (needBt) 900L else 0L)
    }

    /** Panneau Koackpit : un tiers de la largeur, à droite ; le GPS a les deux tiers à gauche. */
    private const val PANEL_FRACTION = 3

    /** Réglage des largeurs par l'outil administrateur. S'il échoue, l'écran reste moitié-moitié. */
    private fun arrange(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(1200)
            val w = app.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            val r = runCatching {
                RootAccess.run(app, RootTool.command(app.packageName, "layout", (w / PANEL_FRACTION).toString()), 20_000)
            }.getOrElse { "ERREUR ${it.message}" }
            WakeLog.add(app, "Disposition conduite : ${r.trim().take(300)}")
        }.start()
    }

    /**
     * Fin de la conduite : referme l'écran partagé (sinon le GPS resterait à gauche avec le bureau Android
     * à droite), puis exécute [then] sur le fil principal.
     */
    fun dismiss(ctx: Context, then: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val r = runCatching { RootAccess.run(app, RootTool.command(app.packageName, "dismiss"), 15_000) }
                .getOrElse { "ERREUR ${it.message}" }
            WakeLog.add(app, "Fin de conduite : ${r.trim().take(200)}")
            then?.let { Handler(Looper.getMainLooper()).post(it) }
        }.start()
    }

    /** Relevé des fenêtres 5 s après le lancement de la conduite (pour régler l'écran partagé). */
    private fun snapshot(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(5000)
            val r = runCatching {
                RootAccess.run(app, "am stack list 2>&1 | head -n 40; echo '---'; " +
                    "dumpsys activity activities 2>&1 | grep -E 'Stack #|mResumedActivity|mBounds=|windowingMode=' | head -n 60", 20_000)
            }.getOrElse { "indisponible : ${it.message}" }
            runCatching { java.io.File(app.filesDir, "driving-snapshot.txt").writeText(r) }
        }.start()
    }
}
