package com.koala.koackpit

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * Thème de Koackpit : mode nuit (foncé) ou mode jour (palette claire mais pas blanche, lisible en plein soleil),
 * une couleur d'accent (vive ou profonde) et un style (Classique, Dégradé, Métal, Pochette, Verre).
 * Les couleurs ci-dessous changent toutes seules quand un réglage change (chaque écran se redessine).
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

    /**
     * Couleur d'accent. Vive : [night] (vive) et [day] (plus soutenue). Profonde ([deep]) : [night] = la teinte
     * foncée (boutons en relief, lueur), [light] = sa version éclaircie pour le texte et les icônes sur fond foncé.
     */
    data class Accent(val name: String, val night: Long, val day: Long, val deep: Boolean = false, val light: Long = night)

    val accents = listOf(
        // Vives
        Accent("Jaune", 0xFFFFCC00, 0xFFF2B400),
        Accent("Ambre", 0xFFFFB020, 0xFFE09200),
        Accent("Orange", 0xFFFF8A3D, 0xFFF2711C),
        Accent("Corail", 0xFFFF6F61, 0xFFE5533F),
        Accent("Rouge", 0xFFFF5A67, 0xFFD62839),
        Accent("Framboise", 0xFFFF4F8B, 0xFFD81B60),
        Accent("Rose", 0xFFFF7AC6, 0xFFD63FA0),
        Accent("Violet", 0xFFB388FF, 0xFF7E57C2),
        Accent("Indigo", 0xFF8C9EFF, 0xFF3F51B5),
        Accent("Bleu", 0xFF4DA3FF, 0xFF2F80ED),
        Accent("Cyan", 0xFF3DD6F5, 0xFF0096C7),
        Accent("Turquoise", 0xFF2EE6C5, 0xFF00A88F),
        Accent("Vert", 0xFF34D27A, 0xFF27AE60),
        Accent("Citron vert", 0xFFB8E62E, 0xFF7CB518),
        Accent("Argent", 0xFFD5DCE6, 0xFF6B7A8F),
        // Profondes (teintes de carrosserie)
        Accent("Bleu Golf", 0xFF1B3A8C, 0xFF1B3A8C, true, 0xFF7FA4FF),
        Accent("Bordeaux", 0xFF7A1430, 0xFF7A1430, true, 0xFFE0566F),
        Accent("Vert anglais", 0xFF14563A, 0xFF14563A, true, 0xFF6FE0A6),
        Accent("Pétrole", 0xFF0F5560, 0xFF0F5560, true, 0xFF6FD6E0),
        Accent("Prune", 0xFF5A1E5C, 0xFF5A1E5C, true, 0xFFC77BCB),
        Accent("Cuivre", 0xFF8A4A22, 0xFF8A4A22, true, 0xFFFFAE73),
        Accent("Or vieilli", 0xFF8C6A1A, 0xFF8C6A1A, true, 0xFFF2C866),
        Accent("Olive", 0xFF4F5A1E, 0xFF4F5A1E, true, 0xFFC8D873),
        Accent("Bleu acier", 0xFF34506E, 0xFF34506E, true, 0xFF9EC3E8),
        Accent("Anthracite", 0xFF3A3F47, 0xFF3A3F47, true, 0xFFC9CED6),
    )
    const val FIRST_DEEP = 15

    /** Styles : 0 Classique, 1 Dégradé, 2 Métal, 3 Pochette, 4 Verre. */
    val styles = listOf("Classique", "Dégradé", "Métal", "Pochette", "Verre")
    const val CLASSIC = 0; const val GRADIENT = 1; const val METAL = 2; const val COVER = 3; const val GLASS = 4

    /** Mode jour affiché en ce moment (décidé par DayNight). */
    val day = mutableStateOf(false)
    val palette = mutableStateOf(0)
    val accent = mutableStateOf(0)
    val style = mutableStateOf(0)
    /** Style Pochette : couleur tirée de la pochette en cours (null : pas de pochette, on garde l'accent choisi). */
    val cover = mutableStateOf<Int?>(null)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        palette.value = p.getInt("palette", 0).coerceIn(0, palettes.lastIndex)
        accent.value = p.getInt("accent", 0).coerceIn(0, accents.lastIndex)
        style.value = p.getInt("style", 0).coerceIn(0, styles.lastIndex)
    }

    private fun save(ctx: Context, k: String, v: Int) = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putInt(k, v).apply()
    fun setPalette(ctx: Context, i: Int) { palette.value = i; save(ctx, "palette", i) }
    fun setAccent(ctx: Context, i: Int) { accent.value = i; save(ctx, "accent", i) }
    fun setStyle(ctx: Context, i: Int) { style.value = i; save(ctx, "style", i) }

    val pal get() = palettes[palette.value]

    /** Accent en cours : celui choisi, ou celui de la pochette avec le style Pochette. */
    val acc: Accent get() {
        val c = cover.value
        if (style.value == COVER && c != null) {
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c, hsv)
            fun hsv(s: Float, v: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, v)).toLong() and 0xFFFFFFFFL
            return Accent("Pochette", hsv(hsv[1].coerceIn(0.5f, 0.75f), 1f), hsv(hsv[1].coerceIn(0.6f, 0.85f), 0.72f))
        }
        return accents[accent.value]
    }

    /** Couleur dominante d'une pochette (teinte la plus présente parmi les pixels colorés). */
    fun dominant(bmp: android.graphics.Bitmap): Int? {
        val src = if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bmp
        val small = android.graphics.Bitmap.createScaledBitmap(src, 24, 24, true)
        val bins = FloatArray(18); val sums = Array(18) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (x in 0 until 24) for (y in 0 until 24) {
            val p = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue
            val b = ((hsv[0] / 20f).toInt()).coerceIn(0, 17)
            val w = hsv[1] * hsv[2]
            bins[b] += w
            sums[b][0] += android.graphics.Color.red(p) * w; sums[b][1] += android.graphics.Color.green(p) * w; sums[b][2] += android.graphics.Color.blue(p) * w
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return null
        if (bins[best] < 3f) return null
        val w = bins[best]
        return android.graphics.Color.rgb((sums[best][0] / w).toInt(), (sums[best][1] / w).toInt(), (sums[best][2] / w).toInt())
    }
}

private val isDay get() = KTheme.day.value
private val st get() = KTheme.style.value

/** Bleu très foncé, identique jour et nuit : boutons foncés. */
val Navy = Color(0xFF00071A)

/** Fond des écrans (couleur unie ; le dégradé du style est dessiné par [screenBg]). */
val Bg: Color get() = when {
    isDay -> Color(KTheme.pal.bg)
    st == KTheme.METAL -> Color(0xFF0B0D11)
    st == KTheme.GRADIENT -> Color(0xFF070B1A)
    st == KTheme.COVER -> Color(0xFF0A0718)
    st == KTheme.GLASS -> Color(0xFF0B2038)
    else -> Navy
}

/** Fond d'écran selon le style : halo, dégradé, reflet. */
fun Modifier.screenBg(): Modifier = this.background(Bg).then(
    if (isDay) Modifier else when (st) {
        KTheme.METAL -> Modifier.background(Brush.radialGradient(listOf(Color(0xFF2A2F38), Color.Transparent), Offset(250f, 120f), 1100f))
        KTheme.COVER -> Modifier.drawBehind {
            drawRect(Brush.radialGradient(listOf(Yellow.copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.78f, size.height * 0.3f), size.width * 0.55f))
        }
        KTheme.GLASS -> Modifier.drawBehind {
            drawRect(Brush.linearGradient(listOf(Color(0xFF0B2A4A), Color(0xFF123B5E), Color(0xFF0A1A2F)), Offset.Zero, Offset(size.width, size.height)))
            drawRect(Brush.radialGradient(listOf(Yellow.copy(alpha = 0.28f), Color.Transparent), Offset(size.width * 0.6f, 0f), size.width * 0.4f))
        }
        KTheme.GRADIENT -> Modifier.drawBehind {
            drawRect(Brush.radialGradient(listOf(Yellow.copy(alpha = 0.12f), Color.Transparent), Offset(size.width, 0f), size.width * 0.6f))
        }
        else -> if (KTheme.acc.deep) Modifier.drawBehind {
            drawRect(Brush.radialGradient(listOf(Yellow.copy(alpha = 0.45f), Color.Transparent), Offset(size.width * 0.8f, 0f), size.width * 0.55f))
        } else Modifier
    }
)

val Card: Color get() = when {
    isDay -> Color(KTheme.pal.card)
    st == KTheme.METAL -> Color(0xFF1A1E25)
    st == KTheme.GRADIENT -> Color(0xFF121A33)
    st == KTheme.COVER -> Color(0xB8140E28)
    st == KTheme.GLASS -> Color.White.copy(alpha = 0.08f)
    KTheme.acc.deep -> Color(0xFF12161F)
    else -> Color(0xFF0E1A33)
}
/** Carte toujours opaque (fenêtres posées par-dessus les autres applis). */
val CardSolid: Color get() = Card.compositeOver(Bg)
val CardHi: Color get() = when {
    isDay -> Color(KTheme.pal.card2)
    st == KTheme.GLASS -> Color.White.copy(alpha = 0.12f)
    st == KTheme.COVER -> Color(0xC81C1438)
    st == KTheme.METAL -> Color(0xFF22272F)
    KTheme.acc.deep -> Color(0xFF181D28)
    else -> Color(0xFF16264A)
}
/** Liseré des cartes selon le style (transparent en Classique). */
val CardBorder: Color get() = when {
    isDay -> Color.Transparent
    st == KTheme.METAL -> Color.White.copy(alpha = 0.09f)
    st == KTheme.GLASS -> Color.White.copy(alpha = 0.18f)
    st == KTheme.COVER -> Yellow.copy(alpha = 0.22f)
    KTheme.acc.deep -> KTheme.acc.let { Color(it.light) }.copy(alpha = 0.14f)
    else -> Color.Transparent
}
/** Fond d'un bouton foncé : la teinte en relief avec une couleur profonde, sinon [BtnDark]. */
fun Modifier.darkBtnBg(shape: Shape): Modifier = if (deepButtons) this.accentBg(shape, glow = false) else this.background(BtnDark, shape)

/** Fond de carte + liseré du style. */
fun Modifier.cardBg(shape: Shape, color: Color = Card): Modifier =
    this.background(color, shape).then(if (CardBorder.alpha > 0f) Modifier.border(1.dp, CardBorder, shape) else Modifier)

/** Fond des boutons ronds et icônes de la barre : foncés dans les deux modes (bien visibles au soleil). */
val BtnDark: Color get() = when {
    isDay -> Color(0xFF0A1324)
    st == KTheme.METAL -> Color(0xFF262B33)
    st == KTheme.GLASS -> Color(0xFF2A4560)
    st == KTheme.COVER -> Color(0xFF241B3A)
    st == KTheme.GRADIENT -> Color(0xFF1A2445)
    KTheme.acc.deep -> Color(0xFF1E232D)
    else -> Color(0xFF16264A)
}
/** Texte principal posé sur les cartes et le fond. */
val Ink: Color get() = if (isDay) Color(KTheme.pal.ink) else Color.White
val Muted: Color get() = if (isDay) Color(KTheme.pal.muted) else Color(0xFF8A96B0)

/** Couleur d'accent : remplissage des boutons et barres. Le nom est resté « Yellow » dans le code. */
val Yellow: Color get() = KTheme.acc.let { Color(if (isDay) it.day else it.night) }
/** Texte ou icône posé SUR l'accent : foncé si l'accent est clair, blanc sinon. */
val OnAccent: Color get() = if (Yellow.luminance() > 0.35f) Navy else Color.White
/**
 * Icône ou texte posé sur un bouton foncé, jour comme nuit. Teinte profonde : blanc (la teinte elle-même
 * colore alors le bouton, voir [deepButtons]) ; sa version éclaircie tirait trop vers le rose.
 */
val AccentOnDark: Color get() = KTheme.acc.let { if (it.deep) Color.White else Color(it.night) }
/** Teinte profonde : les boutons foncés (barre latérale, retour…) prennent la teinte, en relief. */
val deepButtons: Boolean get() = KTheme.acc.deep
/** Accent pour du texte posé sur une carte (version éclaircie la nuit, assombri le jour pour rester lisible). */
val AccentText: Color get() = if (!isDay) KTheme.acc.let { Color(if (it.deep) it.light else it.night) } else {
    var c = Yellow
    while (c.luminance() > 0.16f) c = Color(c.red * 0.85f, c.green * 0.85f, c.blue * 0.85f)
    c
}
val Red: Color get() = if (isDay) Color(0xFFC1121F) else Color(0xFFE63946)

/**
 * Remplissage d'un bouton d'accent selon le style : uni (Classique), dégradé lumineux (Dégradé), relief
 * métallique (Métal et teintes profondes), avec une lueur colorée autour en Dégradé, Pochette et teintes profondes.
 */
fun Modifier.accentBg(shape: Shape = RoundedCornerShape(50), glow: Boolean = true): Modifier {
    val c = Yellow
    val deep = KTheme.acc.deep
    val relief = deep || st == KTheme.METAL
    val brush: Brush = when {
        st == KTheme.GRADIENT -> {
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            val c2 = Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + 330f) % 360f, (hsv[1] + 0.1f).coerceAtMost(1f), hsv[2])))
            Brush.linearGradient(listOf(c, c2))
        }
        relief -> {
            val hi = Color(KTheme.acc.light).copy(alpha = 0.55f).compositeOver(c)
            Brush.verticalGradient(0f to hi, 0.42f to c, 0.75f to c, 1f to Color.Black.copy(alpha = 0.35f).compositeOver(c))
        }
        else -> Brush.linearGradient(listOf(c, c))
    }
    val halo = glow && !isDay && (st == KTheme.GRADIENT || st == KTheme.COVER || deep)
    return (if (halo) this.shadow(10.dp, shape, ambientColor = c, spotColor = c) else this)
        .background(brush, shape)
        .then(if (relief) Modifier.border(1.dp, Color(KTheme.acc.light).copy(alpha = 0.35f), shape) else Modifier)
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
