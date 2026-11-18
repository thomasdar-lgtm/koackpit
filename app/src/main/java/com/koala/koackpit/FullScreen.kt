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
