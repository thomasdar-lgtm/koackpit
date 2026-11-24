package com.koala.koackpit

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Application Koackpit : sait à tout moment si un écran Koackpit est affiché (voir [Front]). */
class KoackpitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Vues de la Golf préparées en fond 20 s après le démarrage (rotation fluide dès l'ouverture de l'écran Voiture)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { GolfViews.prewarm(this) } }, 20_000)
        HomeMode.installCrashGuard(this)
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

/**
 * Remet Koackpit devant sans laisser voir ce qu'il y a dessous (page Bluetooth de l'autoradio) :
 * un cache aux couleurs de Koackpit est posé tout de suite, Koackpit est rappelé (deux façons, et une seconde
 * fois si besoin), et le cache n'est retiré qu'une fois un écran Koackpit réellement affiché (au plus 5 s).
 * Constaté le 21/11 : le simple rappel d'avant ne suffisait pas toujours, la page restait 1 à 2 s visible.
 * [cover] : cache déjà posé par l'appelant (sinon on en pose un).
 */
object ComeBack {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun cover(app: android.content.Context): android.view.View? = runCatching {
        val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val v = android.widget.FrameLayout(app).apply { setBackgroundColor(android.graphics.Color.rgb(0, 7, 26)) }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE,
        )
        wm.addView(v, lp); v
    }.getOrNull()

    /** Sur le fil principal. */
    fun bring(app: android.content.Context, existing: android.view.View? = null) {
        val cover = existing ?: cover(app)
        call(app)
        val start = android.os.SystemClock.elapsedRealtime()
        var retried = false
        fun poll() {
            val t = android.os.SystemClock.elapsedRealtime() - start
            if (Front.visible > 0 || t > 5000) {
                if (Front.visible == 0) WakeLog.add(app, "Retour sur Koackpit : pas confirmé au bout de 5 s")
                // Petit délai : laisse Koackpit se dessiner avant de retirer le cache
                main.postDelayed({ cover?.let { v -> runCatching { (app.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(v) } } }, 250)
                return
            }
            if (t > 1500 && !retried) { retried = true; call(app) }
            main.postDelayed({ poll() }, 150)
        }
        main.postDelayed({ poll() }, 150)
    }

    private fun call(app: android.content.Context) {
        runCatching {
            if (MusicActivity.isOpen()) MusicActivity.restart()
            else app.startActivity(android.content.Intent(app, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        // Et la tâche Koackpit elle-même remise au premier plan (si elle existe encore)
        runCatching {
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.firstOrNull()?.moveToFront()
        }
    }
}
