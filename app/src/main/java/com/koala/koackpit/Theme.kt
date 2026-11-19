package com.koala.koackpit

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * Thème de Koackpit : mode nuit (bleu foncé, l'interface d'origine) ou mode jour (palette claire mais pas
 * blanche, lisible en plein soleil), plus une couleur d'accent. Les couleurs ci-dessous changent toutes seules
 * quand le mode, la palette ou l'accent changent (chaque écran se redessine).
 */
object KTheme {
    /** Palette du mode jour : fond, cartes, carte d'en-tête, texte, texte secondaire, barre latérale. */
    data class Palette(val name: String, val bg: Long, val card: Long, val card2: Long, val ink: Long, val muted: Long, val rail: Long)

    val palettes = listOf(
        Palette("Gris-bleu", 0xFFB9C4D2, 0xFFE9EEF4, 0xFFDDE4EC, 0xFF0A1324, 0xFF3E4C66, 0xFFA7B3C3),
        Palette("Sable", 0xFFCFC6B4, 0xFFF3EEE3, 0xFFE9E2D3, 0xFF111111, 0xFF4A4438, 0xFFBFB5A1),
        Palette("Ardoise", 0xFFA9AEB4, 0xFFE8EAEC, 0xFFDCDFE2, 0xFF15181C, 0xFF464C55, 0xFF989DA4),
        Palette("Sauge", 0xFFB4C2B4, 0xFFECF1EA, 0xFFE0E8DD, 0xFF102016, 0xFF3F5244, 0xFFA2B2A2),
    )

    /** Couleur d'accent : version nuit (vive) et version jour (un peu plus soutenue pour le contraste). */
    data class Accent(val name: String, val night: Long, val day: Long)

    val accents = listOf(
        Accent("Jaune", 0xFFFFCC00, 0xFFF2B400),
        Accent("Bleu", 0xFF4DA3FF, 0xFF2F80ED),
        Accent("Vert", 0xFF34D27A, 0xFF27AE60),
        Accent("Orange", 0xFFFF8A3D, 0xFFF2711C),
        Accent("Rouge", 0xFFFF5A67, 0xFFD62839),
    )

    /** Mode jour affiché en ce moment (décidé par DayNight). */
    val day = mutableStateOf(false)
    val palette = mutableStateOf(0)
    val accent = mutableStateOf(0)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        palette.value = p.getInt("palette", 0).coerceIn(0, palettes.lastIndex)
        accent.value = p.getInt("accent", 0).coerceIn(0, accents.lastIndex)
    }

    fun setPalette(ctx: Context, i: Int) { palette.value = i; ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putInt("palette", i).apply() }
    fun setAccent(ctx: Context, i: Int) { accent.value = i; ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putInt("accent", i).apply() }

    val pal get() = palettes[palette.value]
}

private val isDay get() = KTheme.day.value

/** Bleu très foncé, identique jour et nuit : boutons foncés, texte posé sur l'accent. */
val Navy = Color(0xFF00071A)
/** Fond des écrans. */
val Bg: Color get() = if (isDay) Color(KTheme.pal.bg) else Navy
val Card: Color get() = if (isDay) Color(KTheme.pal.card) else Color(0xFF0E1A33)
val CardHi: Color get() = if (isDay) Color(KTheme.pal.card2) else Color(0xFF16264A)
/** Fond des boutons ronds et icônes de la barre : foncés dans les deux modes (bien visibles au soleil). */
val BtnDark: Color get() = if (isDay) Color(0xFF0A1324) else Color(0xFF16264A)
/** Texte principal posé sur les cartes et le fond. */
val Ink: Color get() = if (isDay) Color(KTheme.pal.ink) else Color.White
val Muted: Color get() = if (isDay) Color(KTheme.pal.muted) else Color(0xFF8A96B0)
/** Couleur d'accent (jaune par défaut). Le nom est resté « Yellow » dans le code. */
val Yellow: Color get() = KTheme.accents[KTheme.accent.value].let { Color(if (isDay) it.day else it.night) }
/** Accent pour du texte posé sur une carte (plus foncé le jour pour rester lisible). */
val AccentText: Color get() = if (isDay) Color(0xFF7A5600).takeIf { KTheme.accent.value == 0 } ?: Yellow else Yellow
val Red: Color get() = if (isDay) Color(0xFFC1121F) else Color(0xFFE63946)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
