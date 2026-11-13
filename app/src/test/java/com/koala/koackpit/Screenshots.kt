package com.koala.koackpit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures des écrans au format réel de l'autoradio (1280×720 à 240 dpi), générées sur GitHub sans la voiture.
 * Elles servent à vérifier le rendu avant chaque livraison visuelle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h440dp-land-hdpi")
class Screenshots {
    private val media = MediaInfo("Chandelier", "Sia", playing = true)

    @Test
    fun accueil() = captureRoboImage("build/screens/accueil.png") {
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {}, speedKmh = 0, onHomeAddr = {}, onWorkAddr = {})
    }

    @Test
    fun accueil_mise_a_jour() = captureRoboImage("build/screens/accueil-maj.png") {
        Box(Modifier.fillMaxSize()) {
            HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
            UpdateChip(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp), "0.6.7", null, "") {}
        }
    }

    @Test
    fun accueil_guidage() = captureRoboImage("build/screens/accueil-guidage.png") {
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {},
            guidance = Guidance("350 m", "Tourner à droite · Avenue du Maréchal Foch", "Arrivée 18:04 · 12 km"), speedKmh = 48)
    }

    @Test
    fun accueil_sans_musique() = captureRoboImage("build/screens/accueil-vide.png") {
        HomeContent("17:42", "Jeudi 12 novembre", "Google Maps", MediaInfo(), RailActions(), {}, {}, {}, {})
    }

    /** Conduite : GPS (simulé en gris) à gauche sur les 2/3, panneau Koackpit à droite. */
    @Test
    fun conduite() = captureRoboImage("build/screens/conduite.png") {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(2f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            Box(Modifier.width(285.dp).fillMaxHeight()) {
                DrivePanel("17:42", media, {}, {}, {}, {}, {}, {}, {}, {}, null)
            }
        }
    }

    @Test
    fun conduite_moitie() = captureRoboImage("build/screens/conduite-moitie.png") {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DrivePanel("17:42", media.copy(sourceIsBt = false), {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
    }
}
