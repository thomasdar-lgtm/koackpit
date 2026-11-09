package com.koala.koackpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Après une mise à jour silencieuse, Android prévient Koackpit : on rouvre l'accueil Koackpit. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Démarrage direct (peut être refusé par Android 10 en arrière-plan), puis par l'accès système
                runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Thread.sleep(1500)
                if (!MainActivity.visible) runCatching { RootAccess.run(app, "am start -n ${app.packageName}/.MainActivity") }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
