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
    private lateinit var nearby: Button
    private lateinit var version: TextView
    private lateinit var update: Button
    @Volatile private var available: PhoneUpdate.Info? = null
    private var checkedAt = 0L

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
        version = text(13f, Color.rgb(138, 150, 176)).apply { text = "Version ${PhoneUpdate.installed(this@MainActivity).second}" }
        col.addView(version)
        // Mise à jour (1.6) : affiché seulement quand une version plus récente existe
        update = Button(this).apply {
            isAllCaps = false; visibility = android.view.View.GONE
            setOnClickListener { startUpdate() }
        }
        col.addView(update)
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
        // Journal radio : Android exige « Appareils à proximité » pour voir les coupures Bluetooth
        nearby = Button(this).apply {
            text = "Autoriser « Appareils à proximité » (journal des coupures)"
            isAllCaps = false
            setOnClickListener {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 2)
                else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        col.addView(nearby)
        col.addView(text(14f, Color.rgb(138, 150, 176)).apply {
            text = "2. Active le partage de connexion du téléphone : l'autoradio s'y connecte et Koackpit vient chercher " +
                "ici le morceau en cours et sa pochette. Rien d'autre ne sort du téléphone."
            gravity = Gravity.START
        })
        setContentView(col)
        // Notification de mise à jour (Android 13+ : à autoriser une fois)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
        handleInstall(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleInstall(intent) }

    /** Retour de l'installateur d'Android : confirmation à afficher, ou résultat. */
    private fun handleInstall(i: Intent?) {
        if (i?.action != PhoneUpdate.ACTION_INSTALL) return
        when (i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { startActivity(it) }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> version.text = "Mise à jour installée"
            else -> {
                update.isEnabled = true
                update.text = "Échec de la mise à jour (${i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"}) : réessayer"
            }
        }
    }

    private fun checkUpdate() {
        if (System.currentTimeMillis() - checkedAt < 10 * 60_000) return
        checkedAt = System.currentTimeMillis()
        Thread {
            val r = runCatching { PhoneUpdate.check(this) }
            h.post {
                available = r.getOrNull()
                val cur = PhoneUpdate.installed(this).second
                version.text = when {
                    r.isFailure -> "Version $cur (vérification impossible, pas d'Internet ?)"
                    available == null -> "Version $cur · à jour"
                    else -> "Version $cur"
                }
                available?.let { update.text = "Mettre à jour vers la version ${it.name}"; update.visibility = android.view.View.VISIBLE; update.isEnabled = true }
                    ?: run { update.visibility = android.view.View.GONE }
            }
        }.start()
    }

    private fun startUpdate() {
        val info = available ?: return
        // Android demande une fois d'autoriser cette appli à installer des applis
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            update.text = "Autorise « Installer des applis inconnues », puis reviens ici et touche à nouveau"
            return
        }
        update.isEnabled = false; update.text = "Téléchargement…"
        Thread {
            val r = runCatching { PhoneUpdate.install(this, info) { p -> h.post { update.text = "Téléchargement : $p %" } } }
            h.post {
                if (r.isFailure) { update.isEnabled = true; update.text = "Échec (${r.exceptionOrNull()?.message}) : réessayer" }
                else update.text = "Installation : confirme dans la fenêtre d'Android"
            }
        }.start()
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
            val bt = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            nearby.visibility = if (bt) android.view.View.GONE else android.view.View.VISIBLE
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

    override fun onResume() { super.onResume(); h.post(tick); checkUpdate() }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
