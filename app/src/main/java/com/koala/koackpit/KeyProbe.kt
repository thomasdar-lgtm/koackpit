package com.koala.koackpit

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateListOf

/**
 * Test des boutons physiques (0.20.4) : ce que Koackpit reçoit quand on tourne / appuie sur les boutons de
 * l'autoradio. Deux sources : les touches qui arrivent à l'écran de Koackpit (utilisables pour lui donner une
 * fonction), et les événements bruts des périphériques d'entrée (getevent, accès système) pour voir ce qui existe
 * même quand le système d'origine garde la touche pour lui.
 */
object KeyProbe {
    val keys = mutableStateListOf<String>()
    @Volatile var on = false

    fun record(e: KeyEvent) {
        if (!on) return
        val act = when (e.action) { KeyEvent.ACTION_DOWN -> "appui"; KeyEvent.ACTION_UP -> "relâché"; else -> "action ${e.action}" }
        keys.add("${KeyEvent.keyCodeToString(e.keyCode)} (code ${e.keyCode}, scan ${e.scanCode}) $act" +
            if (e.repeatCount > 0) " ×${e.repeatCount}" else "")
    }

    fun file(c: android.content.Context) = java.io.File(c.filesDir, "test-boutons.txt")
}
