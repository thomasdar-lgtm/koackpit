package com.koala.koackpit.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Écran unique : état de la liaison avec l'autoradio, et le réglage à activer une fois. */
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var track: TextView
    private lateinit var grant: Button
    private lateinit var restricted: Button
    private lateinit var location: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad); setBackgroundColor(Color.rgb(0, 7, 26))
        }
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(text(28f, Color.rgb(255, 204, 0), true).apply { text = "Koackpit Téléphone" })
        status = text(17f, Color.WHITE); col.addView(status)
        track = text(15f, Color.rgb(138, 150, 176)); col.addView(track)
        grant = Button(this).apply {
            text = "1. Autoriser l'accès aux notifications"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        col.addView(grant)
        // Android 13+ : une appli installée hors Play Store doit d'abord être « autorisée » pour ce réglage
        restricted = Button(this).apply {
            text = "Si le réglage est grisé : infos de l'appli → ⋮ → Autoriser les paramètres restreints"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        col.addView(restricted)
        // Position : « Toujours autoriser », pour que l'autoradio la reçoive écran du téléphone éteint
        location = Button(this).apply {
            text = "Autoriser la position (choisir « Toujours autoriser »)"
            isAllCaps = false
            setOnClickListener {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1)
                else
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        col.addView(location)
        col.addView(text(14f, Color.rgb(138, 150, 176)).apply {
            text = "2. Active le partage de connexion du téléphone : l'autoradio s'y connecte et Koackpit vient chercher " +
                "ici le morceau en cours et sa pochette. Rien d'autre ne sort du téléphone."
            gravity = Gravity.START
        })
        setContentView(col)
    }

    private fun granted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.contains(ComponentName(this, MediaBridge::class.java).flattenToString())
    }

    private val tick = object : Runnable {
        override fun run() {
            val ok = granted()
            grant.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
            restricted.visibility = grant.visibility
            val always = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            location.visibility = if (always) android.view.View.GONE else android.view.View.VISIBLE
            val ago = (System.currentTimeMillis() - MediaBridge.lastClient) / 1000
            status.text = when {
                !ok -> "⚠ Accès aux notifications non autorisé"
                !MediaBridge.running -> "Démarrage du service…"
                MediaBridge.lastClient == 0L -> "✓ Prêt — en attente de l'autoradio (partage de connexion activé ?)"
                ago < 10 -> "✓ Autoradio connecté (${MediaBridge.lastClientIp})"
                else -> "Autoradio vu il y a ${ago} s"
            }
            track.text = if (MediaBridge.title.isEmpty()) "Aucun morceau en cours" else
                "♪ ${MediaBridge.title} — ${MediaBridge.artist}" + (if (MediaBridge.artId.isNotEmpty()) "  (pochette ✓)" else "")
            h.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
