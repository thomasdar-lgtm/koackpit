package com.koala.koackpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
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

/** Golf vue de dessus : les ouvrants ouverts sont dessinés écartés, en jaune. */
@Composable
fun CarTopView(mask: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bw = w * 0.56f; val bh = h * 0.9f
        val left = (w - bw) / 2; val top = (h - bh) / 2
        val body = Color(0xFF3A4C70); val glass = Color(0xFF1B2744); val open = Yellow
        // Carrosserie
        drawRoundRect(body, Offset(left, top), Size(bw, bh), CornerRadius(bw * 0.28f))
        // Pare-brise, toit, lunette
        drawRoundRect(glass, Offset(left + bw * 0.12f, top + bh * 0.27f), Size(bw * 0.76f, bh * 0.13f), CornerRadius(bw * 0.08f))
        drawRoundRect(glass, Offset(left + bw * 0.14f, top + bh * 0.74f), Size(bw * 0.72f, bh * 0.08f), CornerRadius(bw * 0.06f))
        val sw = w * 0.035f
        fun door(x: Float, y0: Float, len: Float, isOpen: Boolean, leftSide: Boolean) {
            if (!isOpen) { drawLine(Color(0xFF5B6E94), Offset(x, y0), Offset(x, y0 + len), sw, StrokeCap.Round); return }
            // Porte ouverte : pivote autour de sa charnière avant
            rotate(if (leftSide) 40f else -40f, Offset(x, y0)) {
                drawLine(open, Offset(x, y0), Offset(x, y0 + len), sw * 1.2f, StrokeCap.Round)
            }
        }
        door(left, top + bh * 0.36f, bh * 0.2f, mask and Doors.FL != 0, true)
        door(left + bw, top + bh * 0.36f, bh * 0.2f, mask and Doors.FR != 0, false)
        door(left, top + bh * 0.57f, bh * 0.16f, mask and Doors.RL != 0, true)
        door(left + bw, top + bh * 0.57f, bh * 0.16f, mask and Doors.RR != 0, false)
        // Capot (haut) et coffre (bas) : rectangle jaune s'ils sont ouverts
        if (mask and Doors.HOOD != 0) drawRoundRect(open, Offset(left + bw * 0.1f, top - h * 0.02f), Size(bw * 0.8f, bh * 0.2f), CornerRadius(bw * 0.2f), style = Stroke(sw))
        if (mask and Doors.TRUNK != 0) drawRoundRect(open, Offset(left + bw * 0.1f, top + bh * 0.84f), Size(bw * 0.8f, bh * 0.2f), CornerRadius(bw * 0.2f), style = Stroke(sw))
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
            CarTopView(mask, Modifier.size(if (compact) 40.dp else 54.dp, if (compact) 52.dp else 70.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(Doors.label(mask), color = Color.White, fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                if (!compact) Text("Golf", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
