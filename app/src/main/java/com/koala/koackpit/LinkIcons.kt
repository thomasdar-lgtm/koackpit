package com.koala.koackpit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** État des liaisons (remplace les icônes de la barre d'état de l'autoradio, masquée par Koackpit). */
data class Links(val bt: Boolean, val wifi: Boolean)

fun readLinks(ctx: Context): Links {
    val bt = (runCatching { CarLink.hfpState() }.getOrNull() ?: 0) >= 3
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    val wifi = runCatching {
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)
    return Links(bt, wifi)
}

/** Mis à jour chaque seconde par le service (pas de boucle dans l'affichage). */
object LinkStatus {
    val state = androidx.compose.runtime.mutableStateOf<Links?>(null)
    fun tick(ctx: Context) { val l = readLinks(ctx); if (state.value != l) state.value = l }
}

private val Off = Color(0xFF6B7A99)

/** Petites icônes Bluetooth et Wi-Fi : blanches si connecté, grises barrées de rouge sinon. */
@Composable
fun LinkIcons(links: Links?, size: Dp = 16.dp) {
    if (links == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(size)) { bluetooth(if (links.bt) Color.White else Off); if (!links.bt) slash() }
        Canvas(Modifier.size(size)) { wifi(if (links.wifi) Color.White else Off); if (!links.wifi) slash() }
    }
}

private fun DrawScope.bluetooth(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.25f, h * 0.30f); lineTo(w * 0.72f, h * 0.70f); lineTo(w * 0.50f, h * 0.90f)
        lineTo(w * 0.50f, h * 0.10f); lineTo(w * 0.72f, h * 0.30f); lineTo(w * 0.25f, h * 0.70f)
    }
    drawPath(p, c, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round))
}

private fun DrawScope.wifi(c: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
    for ((i, r) in listOf(0.46f, 0.31f, 0.16f).withIndex()) {
        val rr = w * r * 2f
        drawArc(c, 225f, 90f, false, Offset(w / 2 - rr / 2, h * 0.86f - rr / 2), Size(rr, rr), style = stroke)
        if (i == 2) drawCircle(c, w * 0.07f, Offset(w / 2, h * 0.86f))
    }
}

private fun DrawScope.slash() {
    drawLine(Color(0xFFE63946), Offset(size.width * 0.1f, size.height * 0.1f), Offset(size.width * 0.9f, size.height * 0.9f),
        strokeWidth = size.width * 0.12f, cap = StrokeCap.Round)
}
