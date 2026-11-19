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
 * Golf 5 vue de dessus, à partir des images fournies par Thomas : la voiture fermée (golf_base) et,
 * pour chaque ouvrant, un calque ne contenant que la partie qui change (porte ouverte, coffre ouvert).
 * Les calques se superposent : n'importe quelle combinaison d'ouvrants s'affiche correctement.
 * Capot : image entière à part (vue moteur), affichée en fondu à la place de la voiture.
 * Toutes les images font 431 × 640 et sont alignées.
 */
@Composable
fun CarTopView(mask: Int, modifier: Modifier = Modifier) {
    @Composable fun fade(bit: Int, name: String) =
        animateFloatAsState(if (mask and bit != 0) 1f else 0f, tween(350), label = name).value
    val fl = fade(Doors.FL, "fl"); val fr = fade(Doors.FR, "fr"); val rl = fade(Doors.RL, "rl"); val rr = fade(Doors.RR, "rr")
    val trunk = fade(Doors.TRUNK, "coffre"); val hood = fade(Doors.HOOD, "capot")
    val base = ImageBitmap.imageResource(R.drawable.golf_base)
    val layers = listOf(
        ImageBitmap.imageResource(R.drawable.golf_fl) to fl, ImageBitmap.imageResource(R.drawable.golf_fr) to fr,
        ImageBitmap.imageResource(R.drawable.golf_rl) to rl, ImageBitmap.imageResource(R.drawable.golf_rr) to rr,
        ImageBitmap.imageResource(R.drawable.golf_trunk) to trunk,
    )
    val capot = ImageBitmap.imageResource(R.drawable.golf_capot)

    Canvas(modifier) {
        val k = minOf(size.width / 431f, size.height / 640f)
        val sz = IntSize((431 * k).toInt(), (640 * k).toInt())
        val off = IntOffset(((size.width - sz.width) / 2).toInt(), ((size.height - sz.height) / 2).toInt())
        val car = 1f - hood
        if (car > 0.01f) {
            drawImage(base, dstOffset = off, dstSize = sz, alpha = car, filterQuality = FilterQuality.High)
            for ((img, t) in layers) if (t > 0.01f) drawImage(img, dstOffset = off, dstSize = sz, alpha = t * car, filterQuality = FilterQuality.High)
        }
        if (hood > 0.01f) drawImage(capot, dstOffset = off, dstSize = sz, alpha = hood, filterQuality = FilterQuality.High)
    }
}

/** Alerte façon CarPlay : apparaît en haut quand un ouvrant est ouvert, disparaît à sa fermeture. */
@Composable
fun DoorAlert(mask: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    AnimatedVisibility(mask != 0, modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Row(
            Modifier.shadow(12.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(BtnDark)
                .border(1.5.dp, Yellow, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarTopView(mask, Modifier.size(if (compact) 54.dp else 70.dp, if (compact) 80.dp else 104.dp))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(Doors.label(mask), color = Color.White, fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                if (!compact) Text("Golf", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
