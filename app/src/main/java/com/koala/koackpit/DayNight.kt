package com.koala.koackpit

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/**
 * Mode jour / nuit selon l'heure (réglable dans Réglages → Thème), et bascule manuelle qui tient jusqu'à l'arrêt
 * de la voiture. À chaque bascule, la luminosité de l'écran passe au maximum le jour et au minimum la nuit.
 * La luminosité se règle aussi à la main (Min, 25 %, 50 %, 75 %, Max) dans la fenêtre Affichage.
 */
object DayNight {
    /** null = automatique ; true = jour forcé ; false = nuit forcée (jusqu'à l'arrêt). */
    val forced = mutableStateOf<Boolean?>(null)
    /** Heures de bascule, en minutes depuis minuit. */
    val dayStart = mutableStateOf(8 * 60)
    val nightStart = mutableStateOf(17 * 60 + 30)
    /** Niveau de luminosité choisi (0 à 4 : Min, 25, 50, 75, Max). */
    val level = mutableStateOf(4)
    /** Fenêtre Affichage ouverte. */
    val panel = mutableStateOf(false)

    private var loaded = false
    private var lastDay: Boolean? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("affichage", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        dayStart.value = p.getInt("jour", 8 * 60)
        nightStart.value = p.getInt("nuit", 17 * 60 + 30)
        level.value = p.getInt("niveau", 4)
        KTheme.load(ctx)
    }

    fun autoDay(): Boolean {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val d = dayStart.value; val n = nightStart.value
        return if (d < n) m in d until n else m >= d || m < n
    }

    /** Appelée chaque seconde par le service : applique le mode, et la luminosité quand le mode change. */
    fun tick(ctx: Context) {
        load(ctx)
        val day = forced.value ?: autoDay()
        if (KTheme.day.value != day) KTheme.day.value = day
        if (lastDay != day) {
            val first = lastDay == null
            lastDay = day
            // Au démarrage on remet aussi la luminosité du mode en cours (l'autoradio peut l'avoir changée)
            setLevel(ctx, if (day) 4 else 0, log = if (first) "démarrage" else "bascule ${if (day) "jour" else "nuit"}")
        }
    }

    /** Arrêt de la voiture : on repasse en automatique. */
    fun onStop() { forced.value = null }

    fun force(ctx: Context, day: Boolean?) {
        forced.value = day
        tick(ctx)
    }

    fun setTimes(ctx: Context, day: Int, night: Int) {
        dayStart.value = ((day % 1440) + 1440) % 1440
        nightStart.value = ((night % 1440) + 1440) % 1440
        sp(ctx).edit().putInt("jour", dayStart.value).putInt("nuit", nightStart.value).apply()
    }

    private val LEVELS = intArrayOf(10, 64, 128, 191, 255)

    /** Luminosité de l'écran (même réglage Android que le bouton DIM de l'autoradio), par l'accès système. */
    fun setLevel(ctx: Context, i: Int, log: String = "manuel") {
        level.value = i
        sp(ctx).edit().putInt("niveau", i).apply()
        val v = LEVELS[i.coerceIn(0, 4)]
        val app = ctx.applicationContext
        Thread {
            val r = runCatching {
                RootAccess.run(app, "settings put system screen_brightness_mode 0; settings put system screen_brightness $v; " +
                    "settings get system screen_brightness", 15_000)
            }.getOrElse { "ERREUR ${it.message}" }
            WakeLog.add(app, "Luminosité ($log) : ${label(i)} → ${r.trim().lines().lastOrNull() ?: "?"}")
        }.start()
    }

    fun label(i: Int) = when (i) { 0 -> "Min"; 4 -> "Max"; else -> "${i * 25} %" }
    fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}

// ---------- Fenêtre « Affichage » (bouton de la barre latérale) ----------

@Composable
fun DisplayPanel(onMode: (Boolean?) -> Unit, onLevel: (Int) -> Unit, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(470.dp).clip(RoundedCornerShape(22.dp)).background(CardSolid).border(1.5.dp, AccentText, RoundedCornerShape(22.dp))
                .clickable(enabled = false) {}.padding(20.dp),
        ) {
            Text("Affichage", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(
                "Automatique : jour de ${DayNight.hhmm(DayNight.dayStart.value)} à ${DayNight.hhmm(DayNight.nightStart.value)} · " +
                    "bascule manuelle jusqu'à l'arrêt",
                color = Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text("MODE", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val f = DayNight.forced.value
                for ((v, label) in listOf(null to "Auto", true to "☀ Jour", false to "☾ Nuit")) {
                    Chip(label, f == v, Modifier.weight(1f)) { onMode(v) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("LUMINOSITÉ DE L'ÉCRAN", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..4) Chip(DayNight.label(i), DayNight.level.value == i, Modifier.weight(1f)) { onLevel(i) }
            }
        }
    }
}

/** Gros bouton de choix : accent si choisi, foncé sinon (lisible jour et nuit). */
@Composable
fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Yellow else BtnDark).clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) OnAccent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black) }
}

// ---------- Réglages → Thème ----------

@Composable
fun ThemeSettings(
    onPalette: (Int) -> Unit, onAccent: (Int) -> Unit, onTimes: (Int, Int) -> Unit, onStyle: (Int) -> Unit = {},
) {
    SettingsSection("Style") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KTheme.styles.forEachIndexed { i, name ->
                val on = KTheme.style.value == i
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .border(2.5.dp, if (on) AccentText else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onStyle(i) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StylePreview(i)
                    Spacer(Modifier.height(4.dp))
                    Text(name, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
            }
        }
        Text(
            when (KTheme.style.value) {
                KTheme.GRADIENT -> "Boutons en dégradé lumineux, avec un halo."
                KTheme.METAL -> "Aluminium brossé : boutons en relief, reflets argentés."
                KTheme.COVER -> "L'accent prend la couleur de la pochette du morceau en cours."
                KTheme.GLASS -> "Cartes translucides façon verre, lueur colorée en fond."
                else -> "Le style d'origine, couleurs unies."
            } + " Le mode jour garde sa palette claire.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
    SettingsSection("Thème · palette du mode jour") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KTheme.palettes.forEachIndexed { i, p ->
                val on = KTheme.palette.value == i
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(p.bg))
                        .border(2.5.dp, if (on) Yellow else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onPalette(i) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(20.dp, 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(p.card)))
                        Box(Modifier.size(20.dp, 14.dp).clip(RoundedCornerShape(4.dp)).background(Color(p.ink)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(p.name, color = Color(p.ink), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        Text("Le mode nuit suit le style et la couleur d'accent.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    SettingsSection("Couleur d'accent") {
        for ((label, range) in listOf("Vives" to (0 until KTheme.FIRST_DEEP), "Profondes" to (KTheme.FIRST_DEEP until KTheme.accents.size))) {
            Text(label.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            for (row in range.toList().chunked(8)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    for (i in row) {
                        val a = KTheme.accents[i]
                        val on = KTheme.accent.value == i
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(if (on) Ink else Color.Transparent).padding(3.dp)
                                .clip(CircleShape).background(Color(if (KTheme.day.value) a.day else a.night))
                                .then(if (a.deep) Modifier.border(1.dp, Color(a.light).copy(alpha = 0.5f), CircleShape) else Modifier)
                                .clickable { onAccent(i) },
                        )
                    }
                    repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text(KTheme.accents[KTheme.accent.value].name + if (KTheme.style.value == KTheme.COVER) " (remplacé par la pochette quand il y en a une)" else "",
            color = Muted, fontSize = 11.sp)
    }
    SettingsSection("Bascule jour / nuit") {
        val d = DayNight.dayStart.value; val n = DayNight.nightStart.value
        TimeRow("☀ Jour à partir de", d) { onTimes(d + it, n) }
        Spacer(Modifier.height(6.dp))
        TimeRow("☾ Nuit à partir de", n) { onTimes(d, n + it) }
        Text("Réglage par quart d'heure, à changer au fil des saisons. Luminosité : Max le jour, Min la nuit.",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TimeRow(label: String, minutes: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StepButton("−") { onStep(-15) }
        Text(DayNight.hhmm(minutes), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(70.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("+") { onStep(15) }
    }
}

@Composable
private fun StepButton(t: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(BtnDark).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black) }
}

/** Aperçu miniature d'un style (fond, carte et bouton d'accent). */
@Composable
private fun StylePreview(i: Int) {
    val acc = Yellow
    val (bg, card) = when (i) {
        KTheme.METAL -> Brush.verticalGradient(listOf(Color(0xFF2A2F38), Color(0xFF0B0D11))) to Color(0xFF1A1E25)
        KTheme.COVER -> Brush.radialGradient(listOf(Color(0xFF6A2A8E), Color(0xFF0A0718))) to Color(0xB8140E28)
        KTheme.GLASS -> Brush.linearGradient(listOf(Color(0xFF0B2A4A), Color(0xFF1C5A7E))) to Color.White.copy(alpha = 0.12f)
        KTheme.GRADIENT -> Brush.linearGradient(listOf(Color(0xFF070B1A), Color(0xFF121A33))) to Color(0xFF121A33)
        else -> Brush.linearGradient(listOf(Navy, Navy)) to Color(0xFF0E1A33)
    }
    val btn: Brush = when (i) {
        KTheme.GRADIENT -> Brush.linearGradient(listOf(acc, Color(0xFFFF3D77)))
        KTheme.METAL -> Brush.verticalGradient(listOf(Color(0xFFF3F5F8), Color(0xFF8B939E), Color(0xFFD9DEE5)))
        KTheme.COVER -> Brush.linearGradient(listOf(Color(0xFFE85D75), Color(0xFF7B2FF7)))
        else -> Brush.linearGradient(listOf(acc, acc))
    }
    Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(5.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(card)
            .then(if (i == KTheme.GLASS || i == KTheme.METAL) Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)) else Modifier)) {
            Box(Modifier.align(Alignment.Center).size(18.dp).clip(CircleShape).background(btn))
        }
    }
}
