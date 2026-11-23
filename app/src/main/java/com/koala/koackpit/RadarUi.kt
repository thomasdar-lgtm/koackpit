package com.koala.koackpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Distance lisible : « 350 m », « 1,4 km ». */
fun fmtDist(m: Int): String = if (m < 1000) "${(m / 50 * 50).coerceAtLeast(50)} m" else "%.1f km".format(m / 1000f).replace('.', ',')

/**
 * Bandeau « Zone de danger » (en haut de l'écran, comme l'alerte des portes). Rouge plein si on roule au-dessus
 * de la vitesse contrôlée.
 */
@Composable
fun RadarAlert(a: Radar.Alert?, modifier: Modifier = Modifier, compact: Boolean = false) {
    // Garde le dernier contenu pendant l'animation de sortie
    val last = remember { arrayOfNulls<Radar.Alert>(1) }
    if (a != null) last[0] = a
    AnimatedVisibility(a != null, modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val x = a ?: last[0] ?: return@AnimatedVisibility
        val red = Color(0xFFE63946)
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier.shadow(12.dp, shape).clip(shape).background(if (x.over) red else BtnDark)
                .border(1.5.dp, if (x.over) Color.White else red, shape)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DangerSign(if (compact) 34 else 46)
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column {
                Text("Zone de danger", color = Color.White, fontSize = if (compact) 14.sp else 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(
                    if (x.over) "Ralentis · encore ${fmtDist(x.leftM)}" else if (compact) "encore ${fmtDist(x.leftM)}" else "${x.kind} · encore ${fmtDist(x.leftM)}",
                    color = if (x.over) Color.White else MutedOnDark, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
            if (x.vma != null) {
                Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
                LimitSign(x.vma, size = if (compact) 34 else 44)
            }
        }
    }
}

/** Panneau de danger français : triangle blanc bordé de rouge, point d'exclamation noir. */
@Composable
fun DangerSign(size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val sw = w * 0.11f
            val p = Path().apply {
                moveTo(w / 2, sw); lineTo(w - sw * 0.8f, h - sw * 0.9f); lineTo(sw * 0.8f, h - sw * 0.9f); close()
            }
            drawPath(p, Color.White)
            drawPath(p, Color(0xFFD62828), style = Stroke(sw, join = StrokeJoin.Round))
            // « ! »
            drawLine(Color.Black, Offset(w / 2, h * 0.38f), Offset(w / 2, h * 0.62f), strokeWidth = w * 0.09f)
            drawCircle(Color.Black, w * 0.055f, Offset(w / 2, h * 0.74f))
        }
    }
}
