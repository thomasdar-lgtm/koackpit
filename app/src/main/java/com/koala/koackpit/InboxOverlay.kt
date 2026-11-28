package com.koala.koackpit

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Carte « fichier reçu du téléphone » : par-dessus n'importe quel écran, en bas à droite (au-dessus de la carte
 * de mise à jour), pendant la réception d'un fichier partagé depuis le S25 : avancement en %, installation,
 * puis résultat (affiché 10 s). Même style que la carte de mise à jour.
 */
object InboxOverlay {
    /** État affiché : texte, avancement (0..1, null = sans barre), erreur, fin (heure de disparition). */
    class State(val text: String, val progress: Float?, val error: Boolean = false, val until: Long = 0L)
    @Volatile var state: State? = null

    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var bar: ProgressBar? = null

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density * 1.18f).toInt()
    private fun round(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(sw, stroke)
    }

    fun show(text: String, progress: Float?) { state = State(text, progress) }
    fun done(text: String, error: Boolean) { state = State(text, null, error, SystemClock.elapsedRealtime() + 10_000) }

    /** Appelée chaque seconde par le service (et à chaque étape de réception). */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        val s = state
        if (s == null || (s.until > 0 && SystemClock.elapsedRealtime() > s.until)) { state = null; hide(app); return }
        if (view == null) create(app) ?: return
        apply(s)
    }

    private fun apply(s: State) {
        title?.text = s.text
        title?.setTextColor(if (s.error) Color.rgb(255, 92, 92) else Color.WHITE)
        bar?.visibility = if (s.progress != null) android.view.View.VISIBLE else android.view.View.GONE
        s.progress?.let { bar?.progress = (it * 100).toInt() }
    }

    /** Carte seule (aussi utilisée pour les captures d'écran). */
    fun build(app: Context, s: State): LinearLayout {
        val yellow = Color.rgb(255, 204, 0); val navy = Color.rgb(0, 7, 26)
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Color.rgb(22, 38, 74), dp(app, 16).toFloat(), Color.argb(180, 255, 204, 0), dp(app, 1))
            setPadding(dp(app, 10), dp(app, 8), dp(app, 12), dp(app, 8))
            elevation = dp(app, 8).toFloat()
        }
        val icon = TextView(app).apply {
            text = "⇩"; textSize = 16f; setTextColor(navy); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = round(yellow, dp(app, 15).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(app, 30), dp(app, 30))
        }
        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(app, 10), 0, 0, 0) }
        val t = TextView(app).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxWidth = dp(app, 260)
        }
        val p = ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(yellow)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(dp(app, 200), dp(app, 6)).apply { topMargin = dp(app, 5) }
        }
        col.addView(t); col.addView(p)
        row.addView(icon); row.addView(col)
        title = t; bar = p
        apply(s)
        return row
    }

    private fun create(app: Context): LinearLayout? = runCatching {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val row = build(app, state ?: return null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(app, 20); y = dp(app, 80) }
        wm.addView(row, lp)
        view = row
        row
    }.getOrNull()

    fun hide(ctx: Context) {
        val v = view ?: return
        runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        view = null; title = null; bar = null
    }
}
