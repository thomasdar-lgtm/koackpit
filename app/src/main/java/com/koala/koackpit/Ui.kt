package com.koala.koackpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
        Modifier.width(88.dp).fillMaxHeight().background(Rail).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(time, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        RailIcon(Icons.Filled.Place, NavBlue, Color.White, active == "drive", a.onDrive)
        RailIcon(null, Yellow, Navy, active == "music", a.onMusic, note = true)
        RailIcon(Icons.Filled.Phone, PhoneGreen, Color.White, active == "phone", a.onPhone)
        RailIcon(Icons.Filled.Info, CardHi, Yellow, active == "car", a.onCar)
        Spacer(Modifier.weight(1f))
        RailIcon(null, CardHi, Color.White, active == "apps", a.onApps, grid = true)
        RailIcon(Icons.Filled.Settings, Card, Muted, active == "settings", a.onSettings)
    }
}

@Composable
fun RailIcon(
    icon: ImageVector?, bg: Color, ink: Color, active: Boolean, onClick: () -> Unit,
    note: Boolean = false, grid: Boolean = false,
) {
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
            .background(if (active) Yellow.copy(alpha = 0.9f) else Color.Transparent).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp)).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                icon != null -> Icon(icon, null, tint = ink, modifier = Modifier.size(30.dp))
                note -> NoteGlyph(ink, 28)
                grid -> GridGlyph(ink, 24)
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
fun CardBox(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp)
    ) { content() }
}

@Composable
fun Caption(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

/** Pochette : pour l'instant un dégradé (la vraie pochette viendra de l'appli compagnon du S25). */
@Composable
fun Artwork(modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE85D75), Color(0xFF7B2FF7)))),
        contentAlignment = Alignment.Center,
    ) { NoteGlyph(Color.White.copy(alpha = 0.85f), 40) }
}

@Composable
fun MediaControls(m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, big: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (big) 18.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlButton(Glyph.PREV, if (big) 60 else 52, false, onPrev)
        CtrlButton(if (m.playing) Glyph.PAUSE else Glyph.PLAY, if (big) 78 else 64, true, onPlay)
        CtrlButton(Glyph.NEXT, if (big) 60 else 52, false, onNext)
    }
}

/** Carte musique de l'accueil (horizontale). */
@Composable
fun MediaCardWide(modifier: Modifier, m: MediaInfo, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    CardBox(modifier, onOpen) {
        Column {
            Caption("En écoute · Bluetooth")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(Modifier.size(104.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 24.sp,
                        fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist.ifEmpty { "Lance la musique sur ton téléphone" }, color = Yellow, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    MediaControls(m, onPrev, onPlay, onNext, big = false)
                }
            }
        }
    }
}

/** Grande carte navigation de l'accueil : lance la conduite (GPS + musique côte à côte). */
@Composable
fun NavCard(modifier: Modifier, navName: String, onDrive: () -> Unit, onGpsOnly: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D3557), Color(0xFF264B6E), Color(0xFF1B2F4F))))
            .clickable(onClick = onDrive)
    ) {
        // Fond « carte » stylisé
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val road = Color(0xFF3A5A80)
            drawLine(road, Offset(-20f, h * 0.68f), Offset(w + 20f, h * 0.44f), strokeWidth = 26f)
            drawLine(road, Offset(w * 0.30f, -20f), Offset(w * 0.52f, h + 20f), strokeWidth = 26f)
            drawLine(road.copy(alpha = 0.5f), Offset(w * 0.75f, -20f), Offset(w * 0.62f, h + 20f), strokeWidth = 14f)
            val p = Offset(w * 0.415f, h * 0.575f)
            drawCircle(NavBlue.copy(alpha = 0.25f), 34f, p)
            drawCircle(Color.White, 20f, p)
            drawCircle(NavBlue, 13f, p)
        }
        Column(Modifier.padding(20.dp)) {
            Caption("Navigation")
            Text(navName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pill("Démarrer la conduite", Yellow, Navy, onDrive)
            Pill("GPS seul", Card.copy(alpha = 0.92f), Color.White, onGpsOnly)
        }
    }
}

@Composable
fun Pill(text: String, bg: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(28.dp)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) { Text(text, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Black) }
}

/** Carte du jour (heure, date) — deviendra la carte Golf quand les données du véhicule seront branchées. */
@Composable
fun TodayCard(modifier: Modifier, time: String, date: String) {
    CardBox(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(time, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, lineHeight = 64.sp)
            Text(date, color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Écrans complets ----------

@Composable
fun HomeContent(
    time: String, date: String, navName: String, media: MediaInfo, rail: RailActions,
    onGpsOnly: () -> Unit, onPrev: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Navy)) {
        AppRail(time, "", rail)
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NavCard(Modifier.weight(1.35f).fillMaxHeight(), navName, rail.onDrive, onGpsOnly)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MediaCardWide(Modifier.weight(1.1f).fillMaxWidth(), media, onPrev, onPlay, onNext, rail.onMusic)
                TodayCard(Modifier.weight(1f).fillMaxWidth(), time, date)
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
        val narrow = maxWidth < 520.dp
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Barre du haut : accueil, heure, fermer
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallRound(Icons.Filled.Home, onHome)
                Spacer(Modifier.weight(1f))
                Text(time, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
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
                    Artwork(Modifier.size(if (narrow) 150.dp else 190.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(media.title.ifEmpty { "Rien en lecture" }, color = Color.White, fontSize = 22.sp,
                            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        if (media.artist.isNotEmpty()) Text(media.artist, color = Yellow, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MediaControls(media, onPrev, onPlay, onNext, big = !narrow)
                    if (onBtSource != null) Pill("Activer la source Bluetooth", Yellow, Navy, onBtSource)
                    if (media.warning.isNotEmpty()) Text(media.warning, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            // Barre d'applis du bas (comme la barre des tâches d'Android Auto)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Rail).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DockIcon(Icons.Filled.Phone, PhoneGreen, Color.White, onPhone)
                DockIcon(Icons.Filled.Info, CardHi, Yellow, onCar)
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(CardHi).clickable(onClick = onApps),
                    contentAlignment = Alignment.Center,
                ) { GridGlyph(Color.White, 22) }
            }
        }
    }
}

@Composable
fun DockIcon(icon: ImageVector, bg: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = ink, modifier = Modifier.size(28.dp)) }
}

@Composable
fun SmallRound(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Yellow, modifier = Modifier.size(24.dp)) }
}
