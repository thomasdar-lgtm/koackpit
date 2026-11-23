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

/** Pictogramme radar (image fournie par Thomas, res/drawable-nodpi/radar_zone.png). */
@Composable
fun RadarSign(size: Int) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(R.drawable.radar_zone), "Zone de danger",
        Modifier.size(size.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}
