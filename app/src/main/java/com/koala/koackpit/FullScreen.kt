package com.koala.koackpit

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Plein écran : masque la barre d'état de l'autoradio (accueil, heure, icônes) tant qu'un écran Koackpit est
 * affiché. Elle réapparaît un instant en glissant depuis le haut de l'écran, puis se cache à nouveau.
 */
fun Activity.fullScreen() {
    runCatching {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Geste partant du bord haut de l'écran (glisser pour faire descendre la barre d'état) : il ne doit pas
 * « toucher » Koackpit (constaté le 18/11 : ça lançait la conduite). Renvoie true si le geste est à ignorer.
 */
class TopEdgeGuard(private val activity: Activity) {
    private var ignoring = false
    fun shouldIgnore(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            ignoring = ev.rawY < 14 * activity.resources.displayMetrics.density
        }
        val r = ignoring
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) ignoring = false
        return r
    }
}
