package com.koala.koackpit

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Son Bluetooth après un réveil de l'autoradio.
 *
 * Constaté au journal du service « car » : à la coupure du contact, le son Bluetooth est coupé ; au réveil la
 * source reste « None » et le son le reste, jusqu'à ce que la page Bluetooth d'origine s'affiche
 * (« enterApp:BT » puis « setBtmMute=false »). C'est la seule action qui l'a rouvert à coup sûr.
 *
 * Koackpit peut être redémarré par Android juste après le réveil (constaté le 17/11) : l'état « à corriger »
 * est donc enregistré (survit aux redémarrages) et une surveillance régulière fait la correction dès que le
 * téléphone est connecté — une seule fois par réveil.
 */
object BtSound {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("koackpit", Context.MODE_PRIVATE)

    /** Temps passé en veille depuis le démarrage d'Android : augmente à chaque mise en veille. */
    private fun sleptMs() = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    fun markNeeded(ctx: Context, why: String) {
        if (!sp(ctx).getBoolean("btFix", true)) WakeLog.add(ctx, "Son Bluetooth : à rouvrir ($why)")
        sp(ctx).edit().putBoolean("btFix", true).apply()
    }

    private var lastHfp = -1
    private var busy = false

    fun check(ctx: Context) {
        val app = ctx.applicationContext
        if (busy) return
        Thread {
            val hfp = CarLink.hfpState() ?: return@Thread
            val p = sp(app)
            // Signaux de réveil, même si Koackpit n'était pas lancé à ce moment-là :
            // téléphone vu déconnecté, ou l'autoradio a dormi depuis la dernière correction.
            if (hfp < 3 && lastHfp >= 3) markNeeded(app, "téléphone déconnecté")
            if (sleptMs() - p.getLong("btFixSlept", 0) > 60_000 && !p.getBoolean("btFix", true)) markNeeded(app, "veille")
            lastHfp = hfp
            val awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (hfp >= 3 && awake && p.getBoolean("btFix", true)) {
                busy = true
                Handler(Looper.getMainLooper()).post { fix(app) }
            }
        }.start()
    }

    private fun cover(app: Context): android.view.View? = runCatching {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val v = android.widget.FrameLayout(app).apply { setBackgroundColor(android.graphics.Color.rgb(0, 7, 26)) }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE,
        )
        wm.addView(v, lp); v
    }.getOrNull()

    /** Page Bluetooth d'origine un instant, puis retour à Koackpit (accueil, ou conduite si elle était ouverte). */
    private fun fix(app: Context) {
        val h = Handler(Looper.getMainLooper())
        val driving = MusicActivity.isOpen()
        WakeLog.add(app, "Son Bluetooth : page Bluetooth un instant (téléphone connecté)")
        // Cache-écran aux couleurs de Koackpit par-dessus tout : la page Bluetooth s'ouvre dessous
        // (elle doit être réellement affichée pour que l'autoradio rouvre le son) sans être vue.
        val cover = cover(app)
        runCatching {
            app.startActivity(Intent().setComponent(SplitScreen.BT_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { WakeLog.add(app, "Page Bluetooth refusée : ${it.message}") }
        sp(app).edit().putBoolean("btFix", false).putLong("btFixSlept", sleptMs()).apply()
        h.postDelayed({
            if (driving) MusicActivity.restart()
            else app.startActivity(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            busy = false
            h.postDelayed({ cover?.let { v -> runCatching { (app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(v) } } }, 700)
        }, 1500)
    }
}
