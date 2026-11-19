package com.koala.koackpit

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Application Koackpit : sait à tout moment si un écran Koackpit est affiché (voir [Front]). */
class KoackpitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(Front)
    }
}

/**
 * Écrans Koackpit visibles en ce moment (accueil, conduite, réglages…). Sert à la surveillance du démarrage,
 * sans passer par l'accès système. Visible = entre onStart et onStop (en écran partagé, toucher Waze ne
 * cache pas le lecteur Koackpit).
 */
object Front : Application.ActivityLifecycleCallbacks {
    @Volatile var visible = 0
        private set

    override fun onActivityStarted(a: Activity) { visible++ }
    override fun onActivityStopped(a: Activity) { visible = (visible - 1).coerceAtLeast(0) }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
