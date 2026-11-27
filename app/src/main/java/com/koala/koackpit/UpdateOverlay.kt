package com.koala.koackpit

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Carte « mise à jour » flottante : par-dessus n'importe quel écran (accueil, réglages, diagnostic, conduite,
 * Waze…), en bas à droite, dès qu'une nouvelle version existe. Gérée par le service (vérifiée chaque seconde).
 */
object UpdateOverlay {
    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var button: TextView? = null

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density * 1.18f).toInt()

    private fun round(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(sw, stroke)
    }

    /** Appelée chaque seconde par le service. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        val upd = UpdateHub.state
        val awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val info = upd.info
        // Jamais pendant que la voiture roule : l'installation redémarre Koackpit (écran figé, Bluetooth et
        // Wi-Fi affichés « non connecté » le temps de la reprise). La carte revient à l'arrêt.
        val moving = (CanBus.freshSpeed() ?: 0) > 3
        val busy0 = upd.progress != null || upd.status.startsWith("Installation")
        if (info == null || !awake || (moving && !busy0)) { hide(app); return }
        val v = view ?: create(app) ?: return
        val status = upd.status
        val p = upd.progress
        title?.text = when {
            p != null -> "Téléchargement ${(p * 100).toInt()} %"
            status.isNotEmpty() -> status.take(60)
            else -> "Mise à jour ${info.version}"
        }
        title?.setTextColor(if (status.startsWith("Échec")) Color.rgb(255, 92, 92) else Color.WHITE)
        val busy = p != null || status.startsWith("Installation")
        button?.visibility = if (busy) View.GONE else View.VISIBLE
        button?.text = if (status.startsWith("Échec")) "Réessayer" else "Installer"
        v.visibility = View.VISIBLE
    }

    private fun create(app: Context): LinearLayout? = runCatching {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val yellow = Color.rgb(255, 204, 0); val navy = Color.rgb(0, 7, 26)
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Color.rgb(22, 38, 74), dp(app, 16).toFloat(), Color.argb(180, 255, 204, 0), dp(app, 1))
            setPadding(dp(app, 10), dp(app, 8), dp(app, 8), dp(app, 8))
            elevation = dp(app, 8).toFloat()
        }
        val icon = TextView(app).apply {
            text = "↻"; textSize = 16f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = round(yellow, dp(app, 15).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(app, 30), dp(app, 30))
        }
        val t = TextView(app).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(app, 10), 0, dp(app, 10), 0); maxWidth = dp(app, 220)
        }
        val b = TextView(app).apply {
            text = "Installer"; textSize = 13f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD
            background = round(yellow, dp(app, 12).toFloat())
            setPadding(dp(app, 12), dp(app, 7), dp(app, 12), dp(app, 7))
            setOnClickListener {
                WakeLog.add(app, "Mise à jour : appui sur Installer (${UpdateHub.state.info?.version})")
                Updater.startInstall(app, UpdateHub.state)
            }
        }
        row.addView(icon); row.addView(t); row.addView(b)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(app, 20); y = dp(app, 20) }
        wm.addView(row, lp)
        view = row; title = t; button = b
        row
    }.getOrNull()

    fun hide(ctx: Context) {
        val v = view ?: return
        runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        view = null; title = null; button = null
    }
}
