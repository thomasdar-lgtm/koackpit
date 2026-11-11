package com.koala.koackpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Après une mise à jour silencieuse, Android prévient Koackpit : on rouvre l'accueil Koackpit. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        WakeService.start(app)   // le service est arrêté par la mise à jour : on le relance
        // Mise à jour faite par l'appli compagnon : c'est elle qui rouvre Koackpit, on ne touche à rien
        val marker = java.io.File(app.filesDir, UpdaterApp.HANDOFF_MARKER)
        if (marker.exists()) { marker.delete(); return }
        val pending = goAsync()
        Thread {
            try {
                // En une seule commande système : trace des écrans (vérification), fermeture de l'écran
                // « Application installée » d'Android, puis réouverture immédiate de Koackpit.
                val out = runCatching {
                    RootAccess.run(
                        app,
                        "logcat -d -v time | grep -E 'START u0|packageinstaller|PackageInstaller' | tail -n 25; " +
                            "am force-stop com.android.packageinstaller; am start -n ${app.packageName}/.MainActivity >/dev/null 2>&1",
                    )
                }.getOrElse {
                    runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    "Accès système indisponible : ${it.message}"
                }
                runCatching { java.io.File(app.filesDir, "update-trace.txt").writeText(out) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
