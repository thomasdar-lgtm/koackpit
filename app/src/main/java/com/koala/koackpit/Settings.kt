package com.koala.koackpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Réglages rangés par catégories : une grille de 6 tuiles (2 × 3) qui montrent déjà l'essentiel.
 * Thème et couleurs, Autoradio : écran à part. Conduite : choix direct sur la tuile. Téléphone : état seul.
 * Mise à jour : la tuile lance la recherche. Diagnostic : la tuile ouvre l'outil.
 * [initial] : catégorie ouverte d'entrée (captures d'écran).
 */
@Composable
fun SettingsContent(
    nav: String, installed: (String) -> Boolean, version: String, update: String?, checking: Boolean,
    onNav: (String) -> Unit, onCheck: () -> Unit, onDiag: () -> Unit, onSlim: () -> Unit, onBack: () -> Unit,
    hideCallBar: Boolean = true, onHideCallBar: (Boolean) -> Unit = {},
    onPalette: (Int) -> Unit = {}, onAccent: (Int) -> Unit = {}, onTimes: (Int, Int) -> Unit = { _, _ -> },
    onStyle: (Int) -> Unit = {},
    homeOn: Boolean = false, onHome: (Boolean) -> Unit = {},
    initial: String? = null,
) {
    var section by remember { mutableStateOf(initial) }
    when (section) {
        "theme" -> ThemeScreen(onPalette, onAccent, onTimes, onStyle) { section = null }
        "autoradio" -> AutoradioScreen(hideCallBar, onHideCallBar, homeOn, onHome, onSlim) { section = null }
        else -> Column(Modifier.fillMaxSize()) {
            Header("Réglages", onBack)
            Column(
                Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeTile(Modifier.weight(1f)) { section = "theme" }
                    DriveTile(Modifier.weight(1f), nav, installed, onNav)
                    AutoradioTile(Modifier.weight(1f), homeOn, hideCallBar) { section = "autoradio" }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhoneTile(Modifier.weight(1f))
                    UpdateTile(Modifier.weight(1f), version, update, checking, onCheck)
                    DiagTile(Modifier.weight(1f), onDiag)
                }
            }
        }
    }
}

// ---------- Tuiles ----------

@Composable
private fun Tile(
    icon: Int?, vector: androidx.compose.ui.graphics.vector.ImageVector?, title: String, modifier: Modifier, onClick: (() -> Unit)?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).cardBg(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).darkBtnBg(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), null, tint = AccentOnDark, modifier = Modifier.size(20.dp))
                else if (vector != null) Icon(vector, null, tint = AccentOnDark, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (onClick != null) Text("›", color = AccentText, fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun TileLine(text: String, color: Color = Muted) =
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun ThemeTile(modifier: Modifier, onClick: () -> Unit) = Tile(R.drawable.ic_rail_daynight, null, "Thème et couleurs", modifier, onClick) {
    // Quelques teintes, la choisie entourée
    val cur = KTheme.accent.value
    val shown = (listOf(cur) + listOf(0, 3, 9, 12, 15, 16, 17).filter { it != cur }).take(7)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in shown) {
            val a = KTheme.accents[i]
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (i == cur) Ink else Color.Transparent).padding(2.dp)
                    .clip(CircleShape).background(Color(if (KTheme.day.value) a.day else a.night)),
            )
        }
    }
    Spacer(Modifier.weight(1f))
    TileLine("${KTheme.styles[KTheme.style.value]} · ${KTheme.accents[cur].name}", Ink)
    TileLine("Jour ${DayNight.hhmm(DayNight.dayStart.value)} · Nuit ${DayNight.hhmm(DayNight.nightStart.value)}")
}

@Composable
private fun DriveTile(modifier: Modifier, nav: String, installed: (String) -> Boolean, onNav: (String) -> Unit) =
    Tile(R.drawable.ic_rail_wheel, null, "Conduite", modifier, null) {
        Text("GPS de la conduite", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((pkg, name) in listOf(Targets.WAZE to "Waze", Targets.MAPS to "Maps")) {
                val on = nav == pkg
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .then(if (on) Modifier.accentBg(RoundedCornerShape(12.dp), glow = false) else Modifier.background(Navy))
                        .clickable { onNav(pkg) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(name, color = if (on) OnAccent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    if (!installed(pkg)) Text("non installé", color = if (on) OnAccent else MutedOnDark, fontSize = 10.sp)
                }
            }
        }
    }

@Composable
private fun AutoradioTile(modifier: Modifier, homeOn: Boolean, hideCallBar: Boolean, onClick: () -> Unit) =
    Tile(R.drawable.ic_rail_settings, null, "Autoradio", modifier, onClick) {
        StateLine("Bureau", if (homeOn) "Koackpit" else "D'origine", homeOn)
        Spacer(Modifier.height(2.dp))
        StateLine("Barre d'appel d'origine", if (hideCallBar) "masquée" else "affichée", hideCallBar)
        Spacer(Modifier.weight(1f))
        TileLine("Allègement des applis de l'autoradio")
    }

@Composable
private fun StateLine(label: String, value: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(value, color = if (on) AccentText else Muted, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PhoneTile(modifier: Modifier) = Tile(R.drawable.ic_rail_phone, null, "Téléphone", modifier, null) {
    val links = LinkStatus.state.value
    val linked = PhoneLink.connected.value
    StateLine("Bluetooth", if (links?.bt == true) "connecté" else "non connecté", links?.bt == true)
    StateLine("Partage de connexion", if (links?.wifi == true) "relié" else "non relié", links?.wifi == true)
    StateLine("Koackpit Téléphone", if (linked) "relié" else "non relié", linked)
    Spacer(Modifier.weight(1f))
    val b = PhoneLink.battery.value
    if (b != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Inset)) {
            Box(Modifier.fillMaxWidth(b.first.coerceIn(0, 100) / 100f).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                .background(if (b.first <= 15) Red else Color(0xFF2ECC71)))
        }
        Spacer(Modifier.width(8.dp))
        Text("${b.first} %" + if (b.second) " ⚡" else "", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
    } else TileLine("Batterie : inconnue (appli non reliée)")
}

@Composable
private fun UpdateTile(modifier: Modifier, version: String, update: String?, checking: Boolean, onCheck: () -> Unit) =
    Tile(null, androidx.compose.material.icons.Icons.Filled.Refresh, "Mise à jour", modifier, onCheck) {
        Text(version, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        TileLine(
            when {
                checking -> "Recherche en cours…"
                update != null -> "Version $update disponible : bouton en bas à droite"
                else -> "Toucher pour vérifier"
            },
            if (update != null) AccentText else Muted,
        )
    }

@Composable
private fun DiagTile(modifier: Modifier, onDiag: () -> Unit) = Tile(null, androidx.compose.material.icons.Icons.Filled.Info, "Diagnostic", modifier, onDiag) {
    val ctx = LocalContext.current
    val lines = remember { WakeLog.read(ctx).lines().take(3).map { l -> l.substringAfter("  ").ifBlank { l } } }
    Column(
        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Navy).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (l in lines) Text(l, color = MutedOnDark, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(6.dp))
    TileLine("Journal, fichier à m'envoyer")
}

// ---------- Écran Thème et couleurs ----------

@Composable
private fun ThemeScreen(
    onPalette: (Int) -> Unit, onAccent: (Int) -> Unit, onTimes: (Int, Int) -> Unit, onStyle: (Int) -> Unit, onBack: () -> Unit,
) {
    // Aperçu jour/nuit : tout l'écran passe dans le mode choisi, le temps d'être ici (rien n'est enregistré)
    DisposableEffect(Unit) { onDispose { DayNight.preview.value = null } }
    Column(Modifier.fillMaxSize()) {
        Header("Thème et couleurs", onBack)
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(250.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).cardBg(RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text("APERÇU", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                MiniPreview(Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val day = KTheme.day.value
                    Chip("☾ Nuit", !day, Modifier.weight(1f)) { DayNight.preview.value = false }
                    Chip("☀ Jour", day, Modifier.weight(1f)) { DayNight.preview.value = true }
                }
                Text("Aperçu seulement : le mode réel suit l'heure.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeSettings(onPalette = onPalette, onAccent = onAccent, onTimes = onTimes, onStyle = onStyle)
            }
        }
    }
}

/** Accueil Koackpit en miniature, dessiné avec le thème en cours. */
@Composable
private fun MiniPreview(modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).screenBg().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(30.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Rail).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF2ECC71)))
            for (r in listOf(R.drawable.ic_rail_wheel, R.drawable.ic_rail_daynight, R.drawable.ic_rail_camera)) {
                Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(7.dp)).darkBtnBg(RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center) { Icon(painterResource(r), null, tint = AccentOnDark, modifier = Modifier.size(14.dp)) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).cardBg(RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("CONDUITE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("78", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(" km/h", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("≈ 690 km", color = AccentText, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).cardBg(RoundedCornerShape(8.dp)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Titre du morceau", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("Artiste", color = AccentText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(BtnDark))
                    Box(Modifier.size(30.dp).accentBg(CircleShape).clip(CircleShape), contentAlignment = Alignment.Center) {
                        Text("▶", color = OnAccent, fontSize = 12.sp)
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(BtnDark))
                }
            }
        }
    }
}

// ---------- Écran Autoradio ----------

@Composable
private fun AutoradioScreen(
    hideCallBar: Boolean, onHideCallBar: (Boolean) -> Unit, homeOn: Boolean, onHome: (Boolean) -> Unit, onSlim: () -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("Autoradio", onBack)
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSection("Bureau de l'autoradio") {
                    TwoChoice("Koackpit", "D'origine", homeOn, onHome)
                    Text("Koackpit : il revient toujours devant (fini la page Bluetooth). Retour au bureau d'origine en un appui.",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                SettingsSection("Barre d'appel de l'autoradio") {
                    TwoChoice("Masquée", "Affichée", hideCallBar, onHideCallBar)
                    Text("Masquée : la fiche d'appel Koackpit la remplace.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsSection("Applis de l'autoradio") {
                    Text("Couper les applis d'origine inutiles (radio, DVD…) pour un autoradio plus léger.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    SettingButton("Alléger l'autoradio", onSlim)
                }
            }
        }
    }
}

/** Deux gros boutons : [yes] (valeur true) et [no] (valeur false). */
@Composable
private fun TwoChoice(yes: String, no: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in listOf(true to yes, false to no)) {
            val on = value == v
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .then(if (on) Modifier.accentBg(RoundedCornerShape(12.dp), glow = false) else Modifier.background(Navy))
                    .clickable { onChange(v) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) OnAccent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black) }
        }
    }
}
