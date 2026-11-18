package com.koala.koackpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Interface style Android Auto / CarPlay : barre d'applis verticale, cartes arrondies sur fond sombre,
 * gros éléments tactiles. Tous les écrans ici sont « sans état » (données passées en paramètre) :
 * ils peuvent être dessinés sur GitHub sans la voiture, pour vérifier le rendu avant chaque livraison.
 */

val NavBlue = Color(0xFF33CCFF)
val PhoneGreen = Color(0xFF2ECC71)
val Rail = Color(0xFF050E24)

data class MediaInfo(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val sourceIsBt: Boolean = true,
    val warning: String = "",
)

data class RailActions(
    val onDrive: () -> Unit = {},
    val onMusic: () -> Unit = {},
    val onPhone: () -> Unit = {},
    val onCar: () -> Unit = {},
    val onApps: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

// ---------- Barre d'applis (gauche) ----------

@Composable
fun AppRail(time: String, active: String, a: RailActions, links: Links? = LinkStatus.state.value) {
    Column(
        Modifier.width(66.dp).fillMaxHeight().background(Rail).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(time, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
        LinkIcons(links)
        PhoneBattery()
        RailIcon(Icons.Filled.Place, NavBlue, Color.White, active == "drive", a.onDrive)
        RailIcon(null, Yellow, Navy, active == "music", a.onMusic, note = true)
        RailIcon(Icons.Filled.Phone, PhoneGreen, Color.White, active == "phone", a.onPhone)
        RailIcon(Icons.Filled.Info, CardHi, Yellow, active == "car", a.onCar)
        Spacer(Modifier.weight(1f))
        RailIcon(Icons.Filled.Settings, CardHi, Yellow, active == "settings", a.onSettings)
        RailIcon(null, CardHi, Color.White, active == "apps", a.onApps, grid = true)
    }
}

@Composable
fun RailIcon(
    icon: ImageVector?, bg: Color, ink: Color, active: Boolean, onClick: () -> Unit,
    note: Boolean = false, grid: Boolean = false,
) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) Yellow.copy(alpha = 0.9f) else Color.Transparent).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                icon != null -> Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
                note -> NoteGlyph(ink, 22)
                grid -> GridGlyph(ink, 19)
            }
        }
    }
}

@Composable
fun NoteGlyph(ink: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        drawCircle(ink, w * 0.18f, Offset(w * 0.28f, w * 0.76f))
        drawCircle(ink, w * 0.18f, Offset(w * 0.76f, w * 0.66f))
        drawRect(ink, Offset(w * 0.40f, w * 0.12f), Size(w * 0.09f, w * 0.64f))
        drawRect(ink, Offset(w * 0.88f, w * 0.04f), Size(w * 0.09f, w * 0.62f))
        drawRect(ink, Offset(w * 0.40f, w * 0.04f), Size(w * 0.57f, w * 0.14f))
    }
}

@Composable
fun GridGlyph(ink: Color, size: Int) {
    Canvas(Modifier.size(size.dp)) {
        val c = this.size.width / 3.4f
        for (i in 0..2) for (j in 0..2) drawRoundRect(
            ink, Offset(i * c * 1.2f, j * c * 1.2f), Size(c, c), CornerRadius(c * 0.3f),
        )
    }
}

// ---------- Cartes ----------

@Composable
fun CardBox(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        content = content,
    )
}

@Composable
fun Caption(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

/** Pochette : pour l'instant un dégradé (la vraie pochette viendra de l'appli compagnon du S25). */
@Composable
fun Artwork(modifier: Modifier, title: String = "", artist: String = "") {
    // Pochette trouvée sur Deezer d'après le titre et l'artiste reçus en Bluetooth ; sinon la note de musique
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val found by androidx.compose.runtime.produceState<ImageBitmap?>(null, title, artist) {
        value = if (title.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CoverArt.fetch(ctx, title, artist) }
    }
    val art = found ?: PhoneLink.artFor(title)
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE85D75), Color(0xFF7B2FF7)))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) androidx.compose.foundation.Image(art, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else NoteGlyph(Color.White.copy(alpha = 0.85f), 28)
    }
}

@Composable
fun MediaControls(m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, big: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlButton(Glyph.PREV, if (big) 48 else 40, false, onPrev)
        CtrlButton(if (m.playing) Glyph.PAUSE else Glyph.PLAY, if (big) 60 else 50, true, onPlay)
        CtrlButton(Glyph.NEXT, if (big) 48 else 40, false, onNext)
    }
}

/** Carte musique de l'accueil (horizontale). */
@Composable
fun MediaCardWide(
    modifier: Modifier, m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit,
    onLyrics: (() -> Unit)? = null,
) {
    CardBox(modifier, onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("En écoute · " + (PhoneLink.appName.value.takeIf { it.isNotEmpty() && PhoneLink.matches(m.title) } ?: "Bluetooth"))
                Spacer(Modifier.weight(1f))
                // Paroles : ouvre le panneau à la place des cartes de droite
                if (onLyrics != null) Text(
                    "♪ Paroles", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Yellow).clickable(onClick = onLyrics)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(Modifier.size(64.dp), m.title, m.artist)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist.ifEmpty { "Musique du téléphone" }, color = Yellow, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TrackProgress(m.title, m.playing, Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MediaControls(m, onPrev, onPlay, onNext, big = false) }
        }
    }
}

/** Carte « En écoute » sur toute la hauteur de droite : grande pochette, titre, progression, commandes. */
@Composable
fun MediaCardTall(
    modifier: Modifier, m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit,
    onLyrics: (() -> Unit)? = null,
) {
    CardBox(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("En écoute · " + (PhoneLink.appName.value.takeIf { it.isNotEmpty() && PhoneLink.matches(m.title) } ?: "Bluetooth"))
                Spacer(Modifier.weight(1f))
                if (onLyrics != null) Text(
                    "♪ Paroles", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Yellow).clickable(onClick = onLyrics)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Artwork(Modifier.size(150.dp), m.title, m.artist)
            Spacer(Modifier.height(12.dp))
            Text(m.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(m.artist.ifEmpty { "Musique du téléphone" }, color = Yellow, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            TrackProgress(m.title, m.playing, Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(4.dp))
            MediaControls(m, onPrev, onPlay, onNext, big = true)
        }
    }
}

/** Barre de progression du morceau, donnée par l'appli Koackpit Téléphone (rien si elle n'est pas connectée). */
@Composable
fun TrackProgress(title: String, btPlaying: Boolean, modifier: Modifier = Modifier) {
    val connected = PhoneLink.connected.value
    var pos by remember { mutableStateOf<Long?>(null) }
    // Boucle seulement quand le téléphone est relié (aucun travail sinon)
    androidx.compose.runtime.LaunchedEffect(title, connected, btPlaying) {
        if (!connected) { pos = null; return@LaunchedEffect }
        while (true) { pos = if (PhoneLink.matches(title)) PhoneLink.positionMs(btPlaying) else null; kotlinx.coroutines.delay(500) }
    }
    val p = pos ?: return
    val d = PhoneLink.duration.value
    if (d <= 0) return
    fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    Column(modifier.fillMaxWidth()) {
        // Un appui sur la barre avance ou recule dans le morceau (commande envoyée au téléphone)
        Box(
            Modifier.fillMaxWidth().height(18.dp)
                .pointerInput(d) { detectTapGestures { o -> PhoneLink.command("seek", (o.x / size.width * d).toLong().coerceIn(0, d)) } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth((p.toFloat() / d).coerceIn(0f, 1f)).fillMaxHeight().background(Yellow))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(fmt(p), color = Muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(fmt(d), color = Muted, fontSize = 10.sp)
        }
    }
}

/** Guidage en cours, lu dans la notification de Waze ou Google Maps. */
data class Guidance(val distance: String, val street: String, val eta: String, val arrow: androidx.compose.ui.graphics.ImageBitmap? = null)

/** Infos de la voiture pour l'accueil (boîtier CAN). [learned] : consommation apprise (sinon estimation par défaut). */
data class CarStats(val fuelL: Int, val rangeKm: Int, val outTemp: Float?, val learned: Boolean)

/**
 * Carte conduite de l'accueil : vitesse de la voiture, carburant et autonomie estimée, température extérieure,
 * et les raccourcis de conduite. (Les indications du GPS n'y figurent plus : utiles seulement avec la carte.)
 */
@Composable
fun NavCard(
    modifier: Modifier, navName: String, speedKmh: Int?, car: CarStats?,
    onDrive: () -> Unit, onHomeAddr: (() -> Unit)?, onWorkAddr: (() -> Unit)?,
    weather: Weather.Forecast? = Weather.state.value,
    limit: Int? = null,
) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D3557), Color(0xFF16294A))))
            .clickable(onClick = onDrive).padding(14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Caption("Conduite · $navName")
            Spacer(Modifier.height(6.dp))
            // Vitesse + température extérieure
            Row(verticalAlignment = Alignment.Bottom) {
                val over = limit != null && speedKmh != null && speedKmh > limit
                Text(speedKmh?.toString() ?: "–", color = if (over) Red else Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, lineHeight = 56.sp)
                Spacer(Modifier.width(6.dp))
                Text("km/h", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                if (limit != null) {
                    Spacer(Modifier.width(14.dp))
                    LimitSign(limit, Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (car?.outTemp != null) Column(horizontalAlignment = Alignment.End) {
                    Text("%.1f °C".format(car.outTemp).replace('.', ','), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Extérieur", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            if (weather != null) { WeatherStrip(weather); Spacer(Modifier.weight(1f)) }
            // Carburant : jauge + litres + autonomie estimée
            if (car != null) {
                val low = car.fuelL <= 8
                Row(verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("Carburant", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${car.fuelL} L", color = if (low) Red else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (car.learned) "Autonomie" else "Autonomie estimée", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("≈ ${car.rangeKm} km", color = if (low) Red else Yellow, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            } else {
                Text("Infos voiture au démarrage du moteur", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Conduite", Yellow, Navy, onDrive)
            }
        }
    }
}

/** Panneau de limitation de vitesse (cercle rouge, chiffre noir sur blanc). */
@Composable
fun LimitSign(limit: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFD62828)).padding(5.dp)
            .clip(androidx.compose.foundation.shape.CircleShape).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(limit.toString(), color = Color.Black, fontSize = if (limit >= 100) 16.sp else 19.sp, fontWeight = FontWeight.Black)
    }
}

/** Météo de la route : temps actuel et pluie à venir (les heures suivantes : retirées, pas utiles). */
@Composable
fun WeatherStrip(w: Weather.Forecast) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Weather.icon(w.code), fontSize = 28.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${Weather.label(w.code)} · ${w.temp} °C", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(w.rainNote, color = if (w.rainSoon) Yellow else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1) }
}

/** Carte du jour (heure, date) — deviendra la carte Golf quand les données du véhicule seront branchées. */
@Composable
fun TodayCard(modifier: Modifier, time: String, date: String, onSettings: () -> Unit) {
    CardBox(modifier) {
        // En haut à gauche : le bas de la carte reste libre pour la carte « mise à jour »
        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Text(time, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
            Text(date, color = Yellow, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        // Réglages : roue dentée en haut à droite de la carte
        Box(Modifier.align(Alignment.TopEnd)) { SmallRound(Icons.Filled.Settings, onSettings) }
    }
}

// ---------- Écrans complets ----------

@Composable
fun HomeContent(
    time: String, date: String, navName: String, media: MediaInfo, rail: RailActions,
    onGpsOnly: () -> Unit, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit,
    speedKmh: Int? = null, car: CarStats? = null,
    onHomeAddr: (() -> Unit)? = null, onWorkAddr: (() -> Unit)? = null,
    weather: Weather.Forecast? = Weather.state.value,
    limit: Int? = SpeedLimit.limit.value,
    lyricsOpen: Boolean = false, lyricsPreview: List<Lyrics.Line>? = null,
) {
    var lyrics by remember { mutableStateOf(lyricsOpen) }
    Row(Modifier.fillMaxSize().background(Navy)) {
        AppRail(time, "", rail)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavCard(Modifier.weight(1.25f).fillMaxHeight(), navName, speedKmh, car, rail.onDrive, onHomeAddr, onWorkAddr, weather, limit)
            if (lyrics) LyricsPanel(Modifier.weight(1f).fillMaxHeight(), media, onPrev, onPlay, onNext, lyricsPreview) { lyrics = false }
            else MediaCardTall(Modifier.weight(1f).fillMaxHeight(), media, onPrev, onPlay, onNext, rail.onMusic,
                onLyrics = if (PhoneLink.connected.value || lyricsPreview != null) ({ lyrics = true }) else null)
        }
    }
}

/** Paroles à la place des cartes « En écoute » et du jour : titre, paroles, commandes de lecture. */
@Composable
fun LyricsPanel(
    modifier: Modifier, m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit,
    preview: List<Lyrics.Line>? = null, onClose: () -> Unit,
) {
    CardBox(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption("Paroles")
                    Text(m.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.artist.isNotEmpty()) Text(m.artist, color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                SmallRound(Icons.Filled.Close, onClose)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) LyricsLines(preview, 2, big = false, modifier = Modifier.fillMaxSize())
                else LyricsView(m.title, m.artist, m.playing, big = false, modifier = Modifier.fillMaxSize(), placeholder = true)
            }
            TrackProgress(m.title, m.playing)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MediaControls(m, onPrev, onPlay, onNext, big = false) }
        }
    }
}

/**
 * Panneau de conduite (à côté du GPS). S'adapte à sa largeur : étroit en écran partagé,
 * large si ouvert seul.
 */
@Composable
fun DrivePanel(
    time: String, media: MediaInfo,
    onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit,
    onHome: () -> Unit, onClose: () -> Unit, onPhone: () -> Unit, onCar: () -> Unit, onApps: () -> Unit,
    onBtSource: (() -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Navy)) {
        val narrow = maxWidth < 300.dp
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Barre du haut : accueil, heure, fermer
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallRound(Icons.Filled.Home, onHome)
                Spacer(Modifier.weight(1f))
                Text(time, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                LinkIcons(LinkStatus.state.value)
                Spacer(Modifier.weight(1f))
                SmallRound(Icons.Filled.Close, onClose)
            }
            // Carte musique. En plein écran : lecture à gauche, paroles à droite (tout l'écran est utilisé)
            CardBox(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Caption(PhoneLink.appName.value.takeIf { it.isNotEmpty() && PhoneLink.matches(media.title) } ?: "Bluetooth")
                        // Pochette plus petite si un bouton ou un message doit aussi tenir dans la carte
                        val extra = onBtSource != null || media.warning.isNotEmpty()
                        Artwork(Modifier.size(if (narrow || extra) 72.dp else 150.dp), media.title, media.artist)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(media.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = if (narrow) 16.sp else 19.sp,
                                fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            if (media.artist.isNotEmpty()) Text(media.artist, color = Yellow, fontSize = if (narrow) 12.sp else 14.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!narrow) TrackProgress(media.title, media.playing, Modifier.padding(horizontal = 24.dp))
                        MediaControls(media, onPrev, onPlay, onNext, big = !narrow)
                        // À côté du GPS : la ligne chantée et la suivante, sous les commandes
                        if (narrow) LyricsView(media.title, media.artist, media.playing, big = false, modifier = Modifier.height(64.dp))
                        if (onBtSource != null) Pill("Activer le Bluetooth", Yellow, Navy, onBtSource)
                        if (media.warning.isNotEmpty()) Text(media.warning, color = Red, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    if (!narrow) Box(Modifier.weight(1.2f).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.Center) {
                        LyricsView(media.title, media.artist, media.playing, big = true, modifier = Modifier.fillMaxSize(), placeholder = true)
                    }
                }
            }
            // Barre d'applis du bas (comme la barre des tâches d'Android Auto) : seulement à côté du GPS
            if (narrow) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Rail).padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DockIcon(Icons.Filled.Phone, PhoneGreen, Color.White, onPhone)
                DockIcon(Icons.Filled.Info, CardHi, Yellow, onCar)
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(CardHi).clickable(onClick = onApps),
                    contentAlignment = Alignment.Center,
                ) { GridGlyph(Color.White, 17) }
            }
        }
    }
}

@Composable
fun DockIcon(icon: ImageVector, bg: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp)) }
}

@Composable
fun SmallRound(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(CardHi).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Yellow, modifier = Modifier.size(20.dp)) }
}

/**
 * Échelle de l'interface : elle est dessinée pour 683×372 dp puis agrandie pour remplir l'écran réel
 * (1280×720 à 240 dpi = 853×440 dp sur l'autoradio). Même rendu quelle que soit la taille de la fenêtre.
 */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    // Basé sur la hauteur : identique en plein écran et dans le panneau de conduite (fenêtre étroite)
    val f = (conf.screenHeightDp / 372f).coerceIn(1f, 1.4f)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * f, d.fontScale),
    ) { content() }
}
