package com.koala.koackpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Encart « Zone de danger », dans la carte de conduite sous la vitesse (et dans le panneau de conduite, sous la barre
 * du haut) : ne recouvre rien. Rouge plein si on roule au-dessus de la vitesse contrôlée.
 */
@Composable
fun RadarAlert(x: Radar.Alert, modifier: Modifier = Modifier, compact: Boolean = false) {
    val red = Color(0xFFE63946)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (x.over) red else red.copy(alpha = 0.14f))
            .border(1.5.dp, red, shape)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadarSign(if (compact) 34 else 42)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text("Zone de danger", color = if (x.over) Color.White else Ink, fontSize = if (compact) 14.sp else 17.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(
                if (x.over) "Ralentis · encore ${fmtDist(x.leftM)}" else if (compact) "encore ${fmtDist(x.leftM)}" else "${x.kind} · encore ${fmtDist(x.leftM)}",
                color = if (x.over) Color.White else Muted, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
        if (x.vma != null) {
            Spacer(Modifier.width(8.dp))
            LimitSign(x.vma, size = if (compact) 34 else 40)
        }
    }
}

/**
 * Pictogramme radar : triangle de danger (blanc bordé de rouge) avec un boîtier de radar et son objectif,
 * et des ondes qui en partent.
 */
@Composable
fun RadarSign(size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            val sw = w * 0.10f
            val p = Path().apply {
                moveTo(w / 2, sw); lineTo(w - sw * 0.8f, h - sw * 0.9f); lineTo(sw * 0.8f, h - sw * 0.9f); close()
            }
            drawPath(p, Color.White)
            drawPath(p, Color(0xFFD62828), style = Stroke(sw, join = StrokeJoin.Round))
            // Boîtier du radar (vu de face) sur son pied
            val bx = w * 0.30f; val by = h * 0.50f; val bw = w * 0.30f; val bh = h * 0.22f
            drawRoundRect(Color.Black, Offset(bx, by), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
            drawRect(Color.Black, Offset(bx + bw * 0.42f, by + bh), androidx.compose.ui.geometry.Size(bw * 0.16f, h * 0.08f))
            // Objectif
            drawCircle(Color.White, bh * 0.30f, Offset(bx + bw * 0.38f, by + bh / 2))
            drawCircle(Color.Black, bh * 0.14f, Offset(bx + bw * 0.38f, by + bh / 2))
            // Ondes
            val cx = bx + bw; val cy = by + bh / 2
            for (i in 1..2) {
                val r = w * 0.06f * i
                drawArc(Color.Black, -45f, 90f, false, Offset(cx - r + w * 0.02f, cy - r), androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(w * 0.035f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
    }
}
