package com.koala.koackpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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
 * Golf 5 (3 ou 5 portes, hayon) vue de dessus, avant en haut. Dessin vectoriel dans un repère de
 * 180 × 240 unités (voiture de 84 de large au centre ; la place autour sert aux portes ouvertes).
 * Les ouvrants ouverts pivotent sur leur charnière avec une animation et passent en jaune.
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

    Canvas(modifier) {
        val uw = 180f; val uh = 240f
        val k = minOf(size.width / uw, size.height / uh)
        withTransform({
            translate((size.width - uw * k) / 2, (size.height - uh * k) / 2)
            scale(k, k, Offset.Zero)
        }) {
            val cx = 90f
            val L = cx - 42f; val R = cx + 42f          // flancs
            val top = 12f; val bottom = 228f            // pare-chocs
            val bodyCol = Color(0xFF41557D); val bodyHi = Color(0xFF52699A)
            val glass = Color(0xFF14203B); val line = Color(0xFF2B3B5E); val tyre = Color(0xFF2A3348)
            val open = Yellow

            // Roues (dépassent légèrement des flancs)
            for (y in listOf(48f, 178f)) for (x in listOf(L - 4f, R - 10f))
                drawRoundRect(tyre, Offset(x, y), Size(14f, 30f), CornerRadius(5f))

            // Carrosserie : avant arrondi, flancs légèrement galbés, arrière de hayon presque droit
            val body = Path().apply {
                moveTo(cx, top)
                cubicTo(cx + 30f, top, R - 2f, top + 6f, R, top + 34f)
                cubicTo(R + 3f, 90f, R + 3f, 160f, R, 200f)
                cubicTo(R - 1f, bottom - 6f, cx + 30f, bottom, cx, bottom)
                cubicTo(cx - 30f, bottom, L + 1f, bottom - 6f, L, 200f)
                cubicTo(L - 3f, 160f, L - 3f, 90f, L, top + 34f)
                cubicTo(L + 2f, top + 6f, cx - 30f, top, cx, top)
                close()
            }
            drawPath(body, Brush.horizontalGradient(listOf(bodyCol, bodyHi, bodyCol), L, R))

            // Capot : nervures
            drawLine(line, Offset(cx - 22f, top + 14f), Offset(cx - 26f, 62f), 1.5f)
            drawLine(line, Offset(cx + 22f, top + 14f), Offset(cx + 26f, 62f), 1.5f)

            // Rétroviseurs
            drawRoundRect(bodyCol, Offset(L - 11f, 80f), Size(12f, 8f), CornerRadius(4f))
            drawRoundRect(bodyCol, Offset(R - 1f, 80f), Size(12f, 8f), CornerRadius(4f))

            // Pare-brise (trapèze), toit, lunette arrière
            val ws = Path().apply {
                moveTo(L + 10f, 94f); lineTo(cx - 26f, 68f); quadraticBezierTo(cx, 63f, cx + 26f, 68f)
                lineTo(R - 10f, 94f); quadraticBezierTo(cx, 89f, L + 10f, 94f); close()
            }
            drawPath(ws, glass)
            drawRoundRect(bodyHi, Offset(L + 12f, 97f), Size(R - L - 24f, 84f), CornerRadius(10f))
            val rw = Path().apply {
                moveTo(L + 12f, 184f); quadraticBezierTo(cx, 180f, R - 12f, 184f)
                lineTo(R - 16f, 204f); quadraticBezierTo(cx, 207f, L + 16f, 204f); close()
            }
            drawPath(rw, glass)
            // Vitres latérales
            drawLine(glass, Offset(L + 6f, 98f), Offset(L + 6f, 178f), 5f, StrokeCap.Round)
            drawLine(glass, Offset(R - 6f, 98f), Offset(R - 6f, 178f), 5f, StrokeCap.Round)

            // Portes : ligne de jonction quand fermée, panneau pivotant quand ouverte
            fun door(hingeY: Float, len: Float, t: Float, leftSide: Boolean) {
                val x = if (leftSide) L else R
                if (t < 0.02f) {
                    drawLine(line, Offset(x + (if (leftSide) 2f else -2f), hingeY + len), Offset(x + (if (leftSide) 10f else -10f), hingeY + len), 1.5f)
                    return
                }
                rotate((if (leftSide) 1f else -1f) * 62f * t, Offset(x, hingeY)) {
                    val w = 9f
                    drawRoundRect(
                        Brush.verticalGradient(listOf(open, Color(0xFFFFA000)), hingeY, hingeY + len),
                        Offset(if (leftSide) x - w else x, hingeY), Size(w, len), CornerRadius(3f), alpha = 0.4f + 0.6f * t,
                    )
                }
                // Ouverture dans la carrosserie
                drawLine(tyre, Offset(x, hingeY + 2f), Offset(x, hingeY + len - 2f), 3f * t, StrokeCap.Round)
            }
            door(92f, 44f, fl, true)
            door(92f, 44f, fr, false)
            door(138f, 38f, rl, true)
            door(138f, 38f, rr, false)

            // Hayon (arrière) et capot (avant) : le panneau se soulève (décalé vers l'extérieur) et passe en jaune
            if (trunk > 0.02f) {
                val y = 186f + 12f * trunk
                drawRoundRect(open, Offset(L + 4f, y), Size(R - L - 8f, bottom - 186f), CornerRadius(16f), alpha = 0.85f * trunk)
                drawRoundRect(glass, Offset(L + 16f, y + 4f), Size(R - L - 32f, 14f), CornerRadius(6f), alpha = 0.6f * trunk)
            }
            if (hood > 0.02f) {
                val y = top - 10f * hood
                drawRoundRect(open, Offset(L + 2f, y), Size(R - L - 4f, 54f), CornerRadius(30f, 24f), alpha = 0.85f * hood)
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
            CarTopView(mask, Modifier.size(if (compact) 56.dp else 76.dp, if (compact) 64.dp else 88.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(Doors.label(mask), color = Color.White, fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                if (!compact) Text("Golf", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
