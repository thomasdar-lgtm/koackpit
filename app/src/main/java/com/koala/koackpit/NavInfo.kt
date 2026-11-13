package com.koala.koackpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat

/** Guidage en cours, partagé avec l'interface (mis à jour par NavListener). */
object NavState {
    var guidance by mutableStateOf<Guidance?>(null)
}

/**
 * Lit la notification de guidage de Waze ou Google Maps (prochaine direction, distance, arrivée).
 * Autorisation accordée automatiquement par l'accès système.
 */
class NavListener : NotificationListenerService() {
    companion object {
        private val NAV_APPS = setOf(Targets.WAZE, Targets.MAPS)
        private val DIST = Regex("""\d+([.,]\d+)?\s?(m|km)\b""", RegexOption.IGNORE_CASE)

        fun enabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName in NAV_APPS) refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName in NAV_APPS) refresh() }

    private fun refresh() {
        val list = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        NavState.guidance = list.filter { it.packageName in NAV_APPS }.firstNotNullOfOrNull { parse(it) }
    }

    /** Une notification de guidage contient une distance (« 350 m », « 1,2 km ») dans son titre ou son texte. */
    private fun parse(sbn: StatusBarNotification): Guidance? {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val (dist, street) = when {
            DIST.containsMatchIn(title) -> DIST.find(title)!!.value to (title.replace(DIST, "").trim(' ', '-', '·') + " " + text).trim()
            DIST.containsMatchIn(text) -> DIST.find(text)!!.value to (title + " " + text.replace(DIST, "").trim(' ', '-', '·')).trim()
            else -> return null
        }
        val arrow = runCatching { iconBitmap(sbn) }.getOrNull()?.asImageBitmap()
        return Guidance(dist, street, sub, arrow)
    }

    /** Flèche de manœuvre : Google Maps la fournit en grande icône de la notification. */
    private fun iconBitmap(sbn: StatusBarNotification): Bitmap? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        val d = icon.loadDrawable(this) ?: return null
        if (d is BitmapDrawable) return d.bitmap
        val b = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height); d.draw(Canvas(b))
        return b
    }
}

/** Vitesse en km/h depuis le GPS de l'autoradio, tant que l'accueil est affiché (null si inconnue). */
@SuppressLint("MissingPermission")
@Composable
fun rememberSpeedKmh(): Int? {
    val ctx = LocalContext.current
    var speed by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                speed = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt().let { if (it < 3) 0 else it } else null
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { speed = null }
        }
        if (ok) runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l) }
        onDispose { runCatching { lm.removeUpdates(l) } }
    }
    return speed
}

/** Autorisations accordées une fois via l'accès système (lecture du guidage, position GPS). */
object NavPermissions {
    fun missing(ctx: Context) = !NavListener.enabled(ctx) ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

    fun grantCommand(ctx: Context): String {
        val p = ctx.packageName
        return "pm grant $p android.permission.ACCESS_FINE_LOCATION; " +
            "cmd notification allow_listener ${ComponentName(p, NavListener::class.java.name).flattenToString()}"
    }
}
