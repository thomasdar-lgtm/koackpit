package com.koala.koackpit

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Messages et appels manqués du téléphone (via l'appli Koackpit Téléphone) : une carte discrète en haut de
 * l'écran, par-dessus tout, 8 secondes chacune, l'une après l'autre. Un appui la ferme.
 */
object NotifOverlay {
    data class Notif(val app: String, val title: String, val text: String, val missedCall: Boolean)

    private val queue = ArrayDeque<Notif>()
    private var root: LinearLayout? = null
    private val h = Handler(Looper.getMainLooper())

    fun show(ctx: Context, list: List<Notif>) {
        val app = ctx.applicationContext
        if (!(app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return
        queue.addAll(list.takeLast(5))
        while (queue.size > 5) queue.removeFirst()
        if (root == null) next(app)
    }

    private fun next(app: Context) {
        hide(app)
        val n = queue.removeFirstOrNull() ?: return
        val v = build(app, n) { h.removeCallbacksAndMessages(null); next(app) }
        runCatching {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(app, 14) }
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            root = v
        }
        h.postDelayed({ next(app) }, 8000)
    }

    private fun hide(app: Context) {
        val v = root ?: return
        runCatching { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        root = null
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density * 1.18f).toInt()
    private fun round(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(sw, stroke)
    }

    /** Vue de la carte (séparée pour les captures d'écran). */
    fun build(app: Context, n: Notif, onTap: () -> Unit): LinearLayout {
        val yellow = Color.rgb(255, 204, 0); val navy = Color.rgb(0, 7, 26); val red = Color.rgb(235, 64, 64)
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(Color.rgb(22, 38, 74), dp(app, 18).toFloat(), Color.argb(150, 255, 204, 0), dp(app, 1))
            elevation = dp(app, 10).toFloat()
            setPadding(dp(app, 10), dp(app, 8), dp(app, 16), dp(app, 8))
            setOnClickListener { onTap() }
        }
        row.addView(TextView(app).apply {
            text = if (n.missedCall) "✆" else (n.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "✉")
            textSize = 17f; setTextColor(if (n.missedCall) Color.WHITE else navy); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = round(if (n.missedCall) red else yellow, dp(app, 19).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(app, 38), dp(app, 38))
        })
        val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(app, 10), 0, 0, 0) }
        col.addView(TextView(app).apply {
            text = (if (n.missedCall) "Appel manqué · " else "") + n.title
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(app, 420)
        })
        val body = if (n.missedCall) n.app else "${n.app} · ${n.text}"
        col.addView(TextView(app).apply {
            text = body; textSize = 13f; setTextColor(Color.rgb(200, 210, 228))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(app, 420)
        })
        row.addView(col)
        return row
    }
}
