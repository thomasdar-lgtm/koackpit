package com.koala.koackpit

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fiche d'appel Koackpit : carte flottante en haut de l'écran, par-dessus n'importe quel écran, pendant un appel
 * (états Bluetooth 4 sortant, 5 entrant, 6 en cours). Pendant un vocal ou l'assistant du téléphone (appel sans
 * nom ni numéro), simple pastille « Micro actif ». Vérifiée chaque seconde par le service.
 */
object CallOverlay {
    /** Ce que la fiche affiche (séparé de l'affichage pour les captures d'écran). */
    data class Call(val state: Int, val name: String?, val number: String?, val seconds: Int, val voiceInCar: Boolean, val vocal: Boolean = false,
                    val keypad: Boolean = false, val typed: String = "")

    private var root: LinearLayout? = null
    private var shown: Call? = null
    private var talkingSince = 0L
    private var lastHfp = -1
    /** Vocal ou assistant : passage direct de « connecté » à « en appel », sans sonnerie ni numérotation (journal du 17/11). */
    private var vocal = false
    private var duration: TextView? = null
    private var keypad = false
    private var typed = ""
    private var vendorLogged = false
    private val BT_BACK = android.content.ComponentName("android.car.app.bt", "android.car.app.bt.BtBackService")

    /** Appelée chaque seconde par le service. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        val hfp = CarLink.hfpState() ?: -1
        val awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (hfp == 6 && lastHfp == 3) vocal = true
        if (hfp < 4) vocal = false
        if (hfp in 0..6) lastHfp = hfp
        if (hfp < 4) { keypad = false; typed = ""; vendorLogged = false }
        if (hfp < 4 || !awake) { talkingSince = 0; hide(app); return }
        // Barre d'appel de l'autoradio : son service est public, on l'arrête (la fiche Koackpit la remplace)
        if (Prefs.hideVendorCall(app)) {
            val was = runCatching { app.stopService(android.content.Intent().setComponent(BT_BACK)) }.getOrElse { false }
            if (was && !vendorLogged) { vendorLogged = true; WakeLog.add(app, "Barre d'appel de l'autoradio coupée") }
        }
        if (hfp == 6 && talkingSince == 0L) talkingSince = SystemClock.elapsedRealtime()
        if (hfp != 6) talkingSince = 0
        val call = Call(
            state = hfp,
            name = CarLink.callName()?.trim()?.takeIf { it.isNotEmpty() },
            number = CarLink.callNumber()?.trim()?.takeIf { it.isNotEmpty() },
            seconds = if (talkingSince > 0) ((SystemClock.elapsedRealtime() - talkingSince) / 1000).toInt() else 0,
            voiceInCar = CarLink.voiceInCar() != false,
            vocal = vocal,
            keypad = keypad, typed = typed,
        )
        // Seule la durée change chaque seconde : on met le texte à jour sans recréer la fiche (elle scintillait)
        if (root != null && shown?.copy(seconds = 0) == call.copy(seconds = 0)) {
            if (call.seconds != shown?.seconds) duration?.text = subText(call)
            shown = call
            return
        }
        show(app, call)
    }

    private fun show(app: Context, call: Call) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = build(app, call) { action ->
            Thread {
                when (action) {
                    "answer" -> CarLink.answer()
                    "hangup" -> CarLink.rejectOrHangup()
                    "switch" -> CarLink.voiceSwitch()
                    "keypad" -> { keypad = !keypad; android.os.Handler(android.os.Looper.getMainLooper()).post { tick(app) } }
                    else -> if (action.startsWith("key:")) {
                        val c = action[4]
                        CarLink.dtmf(c)
                        typed = (typed + c).takeLast(16)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { tick(app) }
                    }
                }
            }.start()
        }
        runCatching {
            val old = root
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(app, 14) }
            wm.addView(v, lp)
            old?.let { runCatching { wm.removeView(it) } }
            root = v; shown = call
        }
    }

    fun hide(ctx: Context) {
        val v = root ?: return
        runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        root = null; shown = null; duration = null
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density * 1.18f).toInt()
    private fun round(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(sw, stroke)
    }

    private val YELLOW = Color.rgb(255, 204, 0)
    private val NAVY = Color.rgb(0, 7, 26)
    private val CARD = Color.rgb(22, 38, 74)
    private val GREEN = Color.rgb(46, 204, 113)
    private val RED = Color.rgb(235, 64, 64)
    private val MUTED = Color.rgb(160, 172, 196)

    private fun subText(call: Call): String {
        val sub = when (call.state) {
            5 -> "Appel entrant"
            4 -> "Appel en cours…"
            else -> "%d:%02d".format(call.seconds / 60, call.seconds % 60) + if (call.voiceInCar) "" else " · sur le téléphone"
        }
        return if (call.name != null && call.number != null && call.state != 6) "$sub · ${call.number}" else sub
    }

    /** Vue de la fiche (ou de la pastille « Micro actif »). [onAction] : "answer", "hangup", "switch". */
    fun build(app: Context, call: Call, onAction: (String) -> Unit): LinearLayout {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(CARD, dp(app, 22).toFloat(), Color.argb(170, 255, 204, 0), dp(app, 1))
            elevation = dp(app, 10).toFloat()
        }
        if (call.vocal) {
            // Vocal / assistant : pastille discrète
            row.setPadding(dp(app, 8), dp(app, 6), dp(app, 14), dp(app, 6))
            row.addView(TextView(app).apply {
                text = "●"; textSize = 13f; setTextColor(RED); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(app, 22), dp(app, 22))
            })
            row.addView(TextView(app).apply {
                text = "Micro actif"; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            return row
        }
        row.setPadding(dp(app, 10), dp(app, 8), dp(app, 8), dp(app, 8))
        val who = call.name ?: call.number ?: "Numéro masqué"
        // Pastille avec l'initiale
        row.addView(TextView(app).apply {
            text = who.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            textSize = 17f; setTextColor(NAVY); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = round(YELLOW, dp(app, 19).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(app, 38), dp(app, 38))
        })
        val texts = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 10), 0, dp(app, 14), 0)
        }
        texts.addView(TextView(app).apply {
            text = who; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            maxWidth = dp(app, 230); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(app).apply {
            text = subText(call)
            textSize = 12f; setTextColor(if (call.state == 5) YELLOW else MUTED)
            maxWidth = dp(app, 260); maxLines = 1
            duration = this
        })
        row.addView(texts)
        fun button(label: String, color: Int, textColor: Int, action: String) = TextView(app).apply {
            text = label; textSize = 13f; setTextColor(textColor); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = round(color, dp(app, 16).toFloat())
            setPadding(dp(app, 14), dp(app, 8), dp(app, 14), dp(app, 8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(app, 6) }
            setOnClickListener { onAction(action) }
        }
        if (call.state == 5) row.addView(button("Décrocher", GREEN, NAVY, "answer"))
        if (call.state == 6) row.addView(button("Clavier", if (call.keypad) YELLOW else NAVY, if (call.keypad) NAVY else YELLOW, "keypad"))
        if (call.state == 6) row.addView(button(if (call.voiceInCar) "Téléphone" else "Voiture", NAVY, YELLOW, "switch"))
        row.addView(button(if (call.state == 5) "Refuser" else "Raccrocher", RED, Color.WHITE, "hangup"))
        if (!call.keypad || call.state != 6) return row
        // Clavier (touches envoyées au téléphone : répondeurs, services clients…)
        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = row.background; elevation = row.elevation
            setPadding(dp(app, 10), dp(app, 8), dp(app, 10), dp(app, 10))
        }
        row.background = null; row.elevation = 0f; row.setPadding(0, 0, 0, 0)
        box.addView(row)
        box.addView(TextView(app).apply {
            text = call.typed.ifEmpty { " " }; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(app, 6), 0, dp(app, 4))
        })
        for (line in listOf("123", "456", "789", "*0#")) {
            val r = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (c in line) r.addView(TextView(app).apply {
                text = c.toString(); textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                background = round(NAVY, dp(app, 10).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(app, 64), dp(app, 34)).apply { setMargins(dp(app, 4), dp(app, 3), dp(app, 4), dp(app, 3)) }
                setOnClickListener { onAction("key:$c") }
            })
            box.addView(r)
        }
        return box
    }
}
