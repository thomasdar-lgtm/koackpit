package com.koala.koackpit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
    fun accueil() = captureRoboImage("build/screens/accueil.png") { Scaled {
        LinkStatus.state.value = Links(bt = true, wifi = true, wifiLevel = 2)
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {}, speedKmh = 0, onHomeAddr = {}, onWorkAddr = {})
    } }

    @Test
    fun accueil_mise_a_jour() = captureRoboImage("build/screens/accueil-maj.png") { Scaled {
        Box(Modifier.fillMaxSize()) {
            HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
            UpdateChip(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp), "0.6.7", null, "") {}
        }
    } }

    @Test
    fun accueil_guidage() = captureRoboImage("build/screens/accueil-guidage.png") { Scaled {
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {},
            speedKmh = 48, car = CarStats(45, 690, 16.5f, false), onHomeAddr = {}, onWorkAddr = {})
    } }

    @Test
    fun accueil_meteo() = captureRoboImage("build/screens/accueil-meteo.png") { Scaled {
        val w = Weather.Forecast(3, 14, "Pluie vers 21:30", true, listOf(
            Weather.Hour("22 h", 61, 13, 70), Weather.Hour("23 h", 80, 12, 85), Weather.Hour("00 h", 3, 12, 30), Weather.Hour("01 h", 2, 11, 10)))
        HomeContent("21:04", "Mercredi 18 novembre", "Waze", media, RailActions(), {}, {}, {}, {},
            speedKmh = 87, car = CarStats(45, 690, 13.5f, true), onHomeAddr = {}, onWorkAddr = {}, weather = w, limit = 80)
    } }

    @Test
    fun accueil_porte() = captureRoboImage("build/screens/accueil-porte.png") { Scaled {
        Box(Modifier.fillMaxSize()) {
            HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
            DoorAlert(Doors.FL or Doors.TRUNK, Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 12.dp))
        }
    } }

    @Test
    fun conduite_porte() = captureRoboImage("build/screens/conduite-porte.png") { Scaled {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(2f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            Box(Modifier.width(285.dp).fillMaxHeight()) {
                DrivePanel("17:42", media, {}, {}, {}, {}, {}, {}, {}, {}, null)
                DoorAlert(Doors.FR, Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 60.dp), compact = true)
            }
        }
    } }

    /** Planche de la Golf en grand : fermée, porte conducteur, deux portes + coffre, tout ouvert. */
    @Test
    fun golf() = captureRoboImage("build/screens/golf.png") { Scaled {
        Row(Modifier.fillMaxSize().background(Navy).padding(16.dp)) {
            for (m in listOf(0, Doors.FL, Doors.FL or Doors.RR or Doors.TRUNK, 31, Doors.HOOD))
                CarTopView(m, Modifier.weight(1f).fillMaxHeight())
        }
    } }

    private val carnet = run {
        fun d(s: String) = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).parse(s)!!.time
        Carnet.Data(
            Carnet.DEFAULT_KINDS,
            listOf(
                Entry("vidange", d("12/03/2026"), 182_400, "Garage du coin, huile 5W30"),
                Entry("ct", d("04/12/2024"), 171_900),
                Entry("air", d("20/05/2024"), 165_000),
                Entry("distribution", 0L, 140_200, "Kit + pompe à eau"),
            ),
            196_850,
        )
    }

    @Test
    fun voiture() = captureRoboImage("build/screens/voiture.png") { Scaled {
        CarScreenContent(carnet, Doors.FL, {}, {}, {}, {}, {})
    } }

    @Test
    fun voiture_vide() = captureRoboImage("build/screens/voiture-vide.png") { Scaled {
        CarScreenContent(Carnet.Data(Carnet.DEFAULT_KINDS, emptyList(), 0), 0, {}, {}, {}, {}, {})
    } }

    @Test
    fun allegement() = captureRoboImage("build/screens/allegement.png") { Scaled {
        val items = Slim.CANDIDATES.mapIndexed { i, c -> SlimItem(c, "android.car.app.exemple$i", enabled = i % 4 != 0) }
        SlimContent(items, null, "", {}, {}, {})
    } }

    @Test
    fun reglages() = captureRoboImage("build/screens/reglages.png") { Scaled {
        Box(Modifier.fillMaxSize().background(Navy)) {
            SettingsContent(Targets.WAZE, { true }, "0.10.7", null, false, {}, {}, {}, {}, {})
        }
    } }

    @Test
    fun accueil_sans_musique() = captureRoboImage("build/screens/accueil-vide.png") { Scaled {
        HomeContent("17:42", "Jeudi 12 novembre", "Google Maps", MediaInfo(), RailActions(), {}, {}, {}, {})
    } }

    /** Conduite : GPS (simulé en gris) à gauche sur les 2/3, panneau Koackpit à droite. */
    @Test
    fun conduite() = captureRoboImage("build/screens/conduite.png") { Scaled {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(2f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            Box(Modifier.width(285.dp).fillMaxHeight()) {
                DrivePanel("17:42", media, {}, {}, {}, {}, {}, {}, {}, {}, null)
            }
        }
    } }

    @Test
    fun conduite_moitie() = captureRoboImage("build/screens/conduite-moitie.png") { Scaled {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                DrivePanel("17:42", media.copy(sourceIsBt = false), {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
    } }

    private val diagApps = listOf(
        AppEntry("android.car.app.canbus", "CarCanbus", true, emptyList(), 9_437_184L, carInfo = true, vendor = true),
        AppEntry("com.waze", "Waze", false, emptyList(), 150_000_000L, carInfo = false, vendor = false),
    )

    @Test
    fun diagnostic() = captureRoboImage("build/screens/diagnostic.png") { Scaled {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Navy).padding(16.dp)) {
            DiagHeader(2, {}, {}, {}, {}, {})
        }
    } }

    @Test
    fun diagnostic_fichier() = captureRoboImage("build/screens/diagnostic-fichier.png") { Scaled {
        val f = java.io.File.createTempFile("koackpit-diag", ".zip")
        ExportPanel(diagApps, emptyList(), false, null, "", f, "http://192.168.43.12:8080/koackpit-diag.zip", "Téléchargements/Koackpit/koackpit-diag.zip",
            {}, {}, {}, {}, qr = qrBitmap("http://192.168.43.12:8080/koackpit-diag.zip"))
    } }

    @Test
    fun diagnostic_creer() = captureRoboImage("build/screens/diagnostic-creer.png") { Scaled {
        ExportPanel(diagApps, emptyList(), false, null, "", null, null, null, {}, {}, {}, {})
    } }

    private fun appel(name: String, call: CallOverlay.Call) = captureRoboImage("build/screens/$name.png") { Scaled {
        Box(Modifier.fillMaxSize()) {
            HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
            androidx.compose.ui.viewinterop.AndroidView(
                { ctx -> CallOverlay.build(ctx, call) {} },
                Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 12.dp),
            )
        }
    } }

    @Test fun appel_entrant() = appel("appel-entrant", CallOverlay.Call(5, "Cindy", "06 12 34 56 78", 0, true))
    @Test fun appel_en_cours() = appel("appel-en-cours", CallOverlay.Call(6, "Garage Dupont", "05 58 00 00 00", 83, true))
    @Test fun appel_clavier() = appel("appel-clavier", CallOverlay.Call(6, "Service client", "3900", 45, true, keypad = true, typed = "12"))
    @Test fun appel_vocal() = appel("appel-vocal", CallOverlay.Call(6, null, "+33612345678", 12, true, vocal = true))

    @Test
    fun paroles() = captureRoboImage("build/screens/paroles.png") { Scaled {
        val l = Lyrics.parse("[00:10.00]Première ligne de la chanson\n[00:14.50]La ligne chantée en ce moment\n[00:19.00]Et celle qui arrive juste après\n[00:23.00]Encore une")
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(Navy)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(2f).fillMaxHeight().background(Color(0xFF2A3F5C)))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                LyricsLines(l, 1, 0, 1, big = false)
                androidx.compose.foundation.layout.Spacer(Modifier.height(30.dp))
                PhoneBattery(78 to false)
                PhoneBattery(15 to false)
                PhoneBattery(54 to true)
            }
        }
    } }

    @Test
    fun paroles_grand() = captureRoboImage("build/screens/paroles-grand.png") { Scaled {
        val l = Lyrics.parse("[00:10.00]Première ligne de la chanson\n[00:14.50]La ligne chantée en ce moment\n[00:19.00]Et celle qui arrive juste après")
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Navy), contentAlignment = androidx.compose.ui.Alignment.Center) {
            LyricsLines(l, 1, 1, 1, big = true)
        }
    } }

    @Test
    fun message() = captureRoboImage("build/screens/message.png") { Scaled {
        Box(Modifier.fillMaxSize()) {
            HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {})
            androidx.compose.ui.viewinterop.AndroidView(
                { ctx -> NotifOverlay.build(ctx, NotifOverlay.Notif("WhatsApp", "Cindy", "J'arrive dans 10 minutes, tu veux que je prenne du pain ?", false)) {} },
                Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 12.dp),
            )
            androidx.compose.ui.viewinterop.AndroidView(
                { ctx -> NotifOverlay.build(ctx, NotifOverlay.Notif("Téléphone", "Garage Dupont", "", true)) {} },
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
    } }

    @Test
    fun accueil_paroles() = captureRoboImage("build/screens/accueil-paroles.png") { Scaled {
        val l = Lyrics.parse((1..8).joinToString("\\n") { "[00:%02d.00]Ligne de paroles numéro $it pour l'essai".format(it * 4) })
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {}, lyricsOpen = true, lyricsPreview = l)
    } }

    @Test
    fun accueil_bouton_paroles() = captureRoboImage("build/screens/accueil-bouton-paroles.png") { Scaled {
        HomeContent("17:42", "Jeudi 12 novembre", "Waze", media, RailActions(), {}, {}, {}, {}, lyricsPreview = emptyList())
    } }
}
