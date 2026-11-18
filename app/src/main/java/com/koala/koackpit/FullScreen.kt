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
 * Faire réapparaître la barre d'état : le cadre de l'autoradio cache le vrai bord haut de l'écran, le doigt ne
 * peut donc pas faire le geste d'Android (constaté le 18/11). Koackpit le fait à sa place : un glisser vers le bas
 * qui part du haut de la partie visible (48 dp) affiche la barre 6 s ; le geste n'est pas transmis à l'écran
 * (avant, il lançait la conduite). Un simple appui au même endroit reste un appui normal.
 */
class TopEdgeGuard(private val activity: Activity) {
    private val d = activity.resources.displayMetrics.density
    private var startY = -1f
    private var swallowed = false
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    fun shouldIgnore(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { swallowed = false; startY = if (ev.rawY < 48 * d) ev.rawY else -1f }
            android.view.MotionEvent.ACTION_MOVE -> if (!swallowed && startY >= 0 && ev.rawY - startY > 30 * d) {
                swallowed = true
                // Annule le geste pour l'écran (aucun appui ne sera déclenché), puis affiche la barre
                val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                activity.window.superDispatchTouchEvent(cancel); cancel.recycle()
                showBarsBriefly()
                return true
            }
        }
        val r = swallowed
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { swallowed = false; startY = -1f }
        return r
    }

    private fun showBarsBriefly() {
        runCatching {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ activity.fullScreen() }, 6000)
    }
}
