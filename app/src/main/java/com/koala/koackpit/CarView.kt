package com.koala.koackpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ouvrants (bits de la trame CAN « portes ») : 0 avant gauche, 1 avant droite, 2 arrière gauche, 3 arrière droite, 4 coffre, 5 capot. */
object Doors {
    const val FL = 1; const val FR = 2; const val RL = 4; const val RR = 8; const val TRUNK = 16; const val HOOD = 32

    fun label(m: Int): String {
        val names = listOfNotNull(
            "conducteur".takeIf { m and FL != 0 }, "passager".takeIf { m and FR != 0 },
            "arrière gauche".takeIf { m and RL != 0 }, "arrière droite".takeIf { m and RR != 0 },
        )
        val parts = mutableListOf<String>()
        if (names.size == 1) parts += "Porte ${names[0]} ouverte"
        else if (names.size > 1) parts += "${names.size} portes ouvertes"
        if (m and TRUNK != 0) parts += "Coffre ouvert"
        if (m and HOOD != 0) parts += "Capot ouvert"
        return parts.joinToString(" · ")
    }
}

/**
 * Golf 5 vue de dessus (image de la voiture de Thomas, fond détouré), avant en haut.
 * Repère de dessin : 580 × 640 unités ; l'image occupe 330 × 640 au centre, la place de chaque côté
 * sert aux portes ouvertes. Positions des ouvrants relevées sur l'image.
 * Les ouvrants ouverts sont surlignés en jaune et pivotent sur leur charnière (animation).
 */
@Composable
fun CarTopView(mask: Int, modifier: Modifier = Modifier) {
    fun anim(bit: Int) = if (mask and bit != 0) 1f else 0f
    val fl by animateFloatAsState(anim(Doors.FL), tween(450), label = "fl")
    val fr by animateFloatAsState(anim(Doors.FR), tween(450), label = "fr")
    val rl by animateFloatAsState(anim(Doors.RL), tween(450), label = "rl")
    val rr by animateFloatAsState(anim(Doors.RR), tween(450), label = "rr")
    val trunk by animateFloatAsState(anim(Doors.TRUNK), tween(450), label = "coffre")
    val hood by animateFloatAsState(anim(Doors.HOOD), tween(450), label = "capot")
    val img = ImageBitmap.imageResource(R.drawable.golf_top)

    Canvas(modifier) {
        val uw = 580f; val uh = 640f
        val k = minOf(size.width / uw, size.height / uh)
        withTransform({
            translate((size.width - uw * k) / 2, (size.height - uh * k) / 2)
            scale(k, k, Offset.Zero)
        }) {
            val ox = 125f
            val left = ox + 32f; val right = ox + 298f
            val open = Yellow; val deep = Color(0xFFFFA000)

            drawImage(img, dstOffset = IntOffset(ox.toInt(), 0), dstSize = IntSize(330, 640), filterQuality = FilterQuality.High)

            fun door(hingeY: Float, len: Float, t: Float, leftSide: Boolean) {
                if (t < 0.02f) return
                val x = if (leftSide) left else right
                // Emplacement de la porte surligné sur la carrosserie
                drawRoundRect(open, Offset(if (leftSide) x - 4f else x - 22f, hingeY), Size(26f, len), CornerRadius(10f), alpha = 0.55f * t)
                // Porte ouverte : pivote autour de sa charnière avant
                rotate((if (leftSide) 1f else -1f) * 58f * t, Offset(x, hingeY)) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(open, deep), hingeY, hingeY + len),
                        Offset(if (leftSide) x - 14f else x, hingeY), Size(14f, len), CornerRadius(7f), alpha = 0.3f + 0.7f * t,
                    )
                }
            }
            door(194f, 136f, fl, true)
            door(194f, 136f, fr, false)
            door(333f, 150f, rl, true)
            door(333f, 150f, rr, false)

            // Hayon et capot : surlignés et légèrement « soulevés » vers l'extérieur
            if (trunk > 0.02f) {
                val o = Offset(ox + 57f, 497f + 16f * trunk); val sz = Size(217f, 130f)
                drawRoundRect(open, o, sz, CornerRadius(30f), alpha = 0.5f * trunk)
                drawRoundRect(open, o, sz, CornerRadius(30f), alpha = trunk, style = Stroke(7f))
            }
            if (hood > 0.02f) {
                val o = Offset(ox + 53f, 13f - 16f * hood); val sz = Size(227f, 139f)
                drawRoundRect(open, o, sz, CornerRadius(70f, 40f), alpha = 0.5f * hood)
                drawRoundRect(open, o, sz, CornerRadius(70f, 40f), alpha = hood, style = Stroke(7f))
            }
        }
    }
}

/** Alerte façon CarPlay : apparaît en haut quand un ouvrant est ouvert, disparaît à sa fermeture. */
@Composable
fun DoorAlert(mask: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    AnimatedVisibility(mask != 0, modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Row(
            Modifier.shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(CardHi)
                .border(1.5.dp, Yellow, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarTopView(mask, Modifier.size(if (compact) 64.dp else 86.dp, if (compact) 70.dp else 94.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(Doors.label(mask), color = Color.White, fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                if (!compact) Text("Golf", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
