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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun AppRail(time: String, active: String, a: RailActions) {
    Column(
        Modifier.width(66.dp).fillMaxHeight().background(Rail).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(time, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
        RailIcon(Icons.Filled.Place, NavBlue, Color.White, active == "drive", a.onDrive)
        RailIcon(null, Yellow, Navy, active == "music", a.onMusic, note = true)
        RailIcon(Icons.Filled.Phone, PhoneGreen, Color.White, active == "phone", a.onPhone)
        RailIcon(Icons.Filled.Info, CardHi, Yellow, active == "car", a.onCar)
        Spacer(Modifier.weight(1f))
        RailIcon(null, CardHi, Color.White, active == "apps", a.onApps, grid = true)
    }
}

@Composable
fun RailIcon(
    icon: ImageVector?, bg: Color, ink: Color, active: Boolean, onClick: () -> Unit,
    note: Boolean = false, grid: Boolean = false,
) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
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
fun Artwork(modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE85D75), Color(0xFF7B2FF7)))),
        contentAlignment = Alignment.Center,
    ) { NoteGlyph(Color.White.copy(alpha = 0.85f), 28) }
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
fun MediaCardWide(modifier: Modifier, m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    CardBox(modifier, onOpen) {
        Column {
            Caption("En écoute · Bluetooth")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist.ifEmpty { "Musique du téléphone" }, color = Yellow, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MediaControls(m, onPrev, onPlay, onNext, big = false) }
        }
    }
}

/** Guidage en cours, lu dans la notification de Waze ou Google Maps. */
data class Guidance(val distance: String, val street: String, val eta: String, val arrow: androidx.compose.ui.graphics.ImageBitmap? = null)

/**
 * Carte navigation de l'accueil, sans fausse carte : Android n'autorise pas l'affichage de Waze dans
 * Koackpit. Pendant un trajet : la prochaine instruction (lue dans la notification du GPS).
 * Sinon : vitesse et raccourcis. La vraie carte est dans le mode conduite.
 */
@Composable
fun NavCard(
    modifier: Modifier, navName: String, guidance: Guidance?, speedKmh: Int?,
    onDrive: () -> Unit, onHomeAddr: (() -> Unit)?, onWorkAddr: (() -> Unit)?,
) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D3557), Color(0xFF16294A))))
            .clickable(onClick = onDrive).padding(14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Caption(if (guidance != null) "Guidage · $navName" else "Navigation · $navName")
            Spacer(Modifier.height(10.dp))
            if (guidance != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(NavBlue), contentAlignment = Alignment.Center) {
                        if (guidance.arrow != null) androidx.compose.foundation.Image(guidance.arrow, null, Modifier.size(56.dp))
                        else Icon(Icons.Filled.Place, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(guidance.distance, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text(guidance.street, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (guidance.eta.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(guidance.eta, color = Yellow, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            } else {
                Text("Où va-t-on ?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text("La carte s'ouvre avec la conduite, à côté de la musique.", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            // Vitesse (GPS de l'autoradio)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(speedKmh?.toString() ?: "–", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp)
                Spacer(Modifier.width(6.dp))
                Text("km/h", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (guidance != null) "Voir la carte" else "Conduite", Yellow, Navy, onDrive)
                if (guidance == null && onHomeAddr != null) Pill("Maison", Card.copy(alpha = 0.92f), Color.White, onHomeAddr)
                if (guidance == null && onWorkAddr != null) Pill("Travail", Card.copy(alpha = 0.92f), Color.White, onWorkAddr)
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
    guidance: Guidance? = null, speedKmh: Int? = null,
    onHomeAddr: (() -> Unit)? = null, onWorkAddr: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxSize().background(Navy)) {
        AppRail(time, "", rail)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavCard(Modifier.weight(1.25f).fillMaxHeight(), navName, guidance, speedKmh, rail.onDrive, onHomeAddr, onWorkAddr)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaCardWide(Modifier.weight(1.15f).fillMaxWidth(), media, onPrev, onPlay, onNext, rail.onMusic)
                TodayCard(Modifier.weight(1f).fillMaxWidth(), time, date, rail.onSettings)
            }
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
                Spacer(Modifier.weight(1f))
                SmallRound(Icons.Filled.Close, onClose)
            }
            // Carte musique
            CardBox(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Caption("Bluetooth")
                    // Pochette plus petite si un bouton ou un message doit aussi tenir dans la carte
                    val extra = onBtSource != null || media.warning.isNotEmpty()
                    Artwork(Modifier.size(if (narrow || extra) 72.dp else 110.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(media.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        if (media.artist.isNotEmpty()) Text(media.artist, color = Yellow, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MediaControls(media, onPrev, onPlay, onNext, big = !narrow)
                    if (onBtSource != null) Pill("Activer le Bluetooth", Yellow, Navy, onBtSource)
                    if (media.warning.isNotEmpty()) Text(media.warning, color = Red, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
            // Barre d'applis du bas (comme la barre des tâches d'Android Auto)
            Row(
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
