package com.koala.koackpit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
 * Captures des écrans au format de l'autoradio (1024×600), générées sur GitHub sans la voiture.
 * Elles servent à vérifier le rendu avant chaque livraison visuelle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
class Screenshots {
    private val media = MediaInfo("Chandelier", "Sia", playing = true)

    @Test
    fun accueil() = captureRoboImage("build/screens/accueil.png") {
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
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
            Box(Modifier.width(340.dp).fillMaxHeight()) {
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
