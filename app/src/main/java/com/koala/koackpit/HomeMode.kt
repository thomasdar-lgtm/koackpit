package com.koala.koackpit

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Koackpit comme bureau de l'autoradio (au lieu du bureau d'origine « launcher3 »).
 *
 * Intérêt : quand l'autoradio arrête Koackpit (≈ 40 s après le démarrage, mises à jour), Android rouvre le
 * bureau… donc Koackpit, au lieu de laisser voir la page Bluetooth restée derrière.
 * Mécanisme : l'entrée « bureau » est un alias de l'accueil (.Home), désactivé par défaut. L'activer le rend
 * candidat, puis l'accès système le désigne comme bureau par défaut. Le couper suffit à revenir au bureau
 * d'origine (seul candidat restant) : pas besoin de l'accès système pour revenir en arrière.
 * Garde-fou : 3 plantages de Koackpit en 3 minutes → le bureau d'origine revient tout seul.
 */
object HomeMode {
    private const val ORIGIN = "com.android.launcher3/org.cyanogenmod.trebuchet.CustomHomeLauncher"
    private fun alias(ctx: Context) = ComponentName(ctx.packageName, "${ctx.packageName}.Home")

    fun isOn(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Hors fil principal (accès système). Renvoie un compte rendu court. */
    fun enable(ctx: Context): String {
        val app = ctx.applicationContext
        app.packageManager.setComponentEnabledSetting(alias(app), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val out = runCatching {
            RootAccess.run(app, "cmd package set-home-activity ${alias(app).flattenToString()} 2>&1; " +
                "cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN 2>&1 | grep -m 2 -E 'packageName|name='")
        }.getOrElse { "ERREUR ${it.message}" }.trim().replace("\n", " ").replace('\n', ' ')
        val ok = out.contains("packageName=${app.packageName}")
        if (!ok) disable(app, "échec de l'activation")
        WakeLog.add(app, "Bureau Koackpit : ${if (ok) "activé" else "NON activé"} ($out)")
        return if (ok) "activé" else "échec"
    }

    fun disable(ctx: Context, why: String = "réglage") {
        val app = ctx.applicationContext
        app.packageManager.setComponentEnabledSetting(alias(app), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        // Bureau d'origine redésigné (au cas où Android demanderait sinon lequel choisir)
        Thread { runCatching { RootAccess.run(app, "cmd package set-home-activity $ORIGIN 2>&1") } }.start()
        WakeLog.add(app, "Bureau d'origine rétabli ($why)")
    }

    // ---- Garde-fou contre les plantages en boucle ----

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bureau", Context.MODE_PRIVATE)

    /** À appeler au tout début du lancement de l'appli. */
    fun installCrashGuard(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val recent = (sp(app).getString("plantages", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 3 * 60_000 }
        if (recent.size >= 3 && isOn(app)) {
            sp(app).edit().putString("plantages", "").commit()
            disable(app, "3 plantages de suite")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val list = (recent + System.currentTimeMillis()).takeLast(5).joinToString(",")
                sp(app).edit().putString("plantages", list).commit()
            }
            previous?.uncaughtException(t, e)
        }
    }
}
