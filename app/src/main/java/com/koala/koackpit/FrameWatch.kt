package com.koala.koackpit

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics

/**
 * Surveillance de l'affichage de l'accueil (relevé seulement, aucun effet) : le 28/11 au démarrage, l'écran
 * restait figé alors que les appuis marchaient (lecture/pause). Note au journal :
 * - toute image qui a mis plus d'1 s à s'afficher, avec où le temps est passé (calcul, dessin, carte graphique) ;
 * - un appui suivi d'aucune nouvelle image pendant 3 s alors que l'accueil est au premier plan.
 */
object FrameWatch {
    private val thread by lazy { HandlerThread("frames").apply { start() } }
    @Volatile private var lastFrame = 0L
    @Volatile private var lastTouch = 0L
    private var logged = 0

    fun attach(a: Activity) {
        val h = Handler(thread.looper)
        runCatching {
            a.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
                lastFrame = SystemClock.uptimeMillis()
                val total = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
                if (total > 1000 && logged < 40) {
                    logged++
                    fun ms(k: Int) = m.getMetric(k) / 1_000_000
                    WakeLog.add(a.applicationContext, "Affichage lent : image en ${total} ms (attente ${ms(FrameMetrics.UNKNOWN_DELAY_DURATION)}, " +
                        "calcul ${ms(FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(FrameMetrics.ANIMATION_DURATION) + ms(FrameMetrics.INPUT_HANDLING_DURATION)}, " +
                        "dessin ${ms(FrameMetrics.DRAW_DURATION)}, envoi ${ms(FrameMetrics.SYNC_DURATION) + ms(FrameMetrics.COMMAND_ISSUE_DURATION)}, " +
                        "carte graphique ${ms(FrameMetrics.SWAP_BUFFERS_DURATION)})")
                }
            }, h)
        }
    }

    /** Appelé à chaque appui sur l'accueil. */
    fun touched(a: Activity) {
        val t = SystemClock.uptimeMillis()
        if (t - lastTouch < 1000) return
        lastTouch = t
        // Force une image (même si rien ne change à l'écran) : sans image dans 3 s, l'affichage est vraiment figé
        a.window.decorView.postInvalidate()
        val app = a.applicationContext
        Handler(thread.looper).postDelayed({
            if (MainActivity.visible && lastFrame < t && logged < 40) {
                logged++
                val since = if (lastFrame == 0L) "jamais" else "${(SystemClock.uptimeMillis() - lastFrame) / 1000} s"
                WakeLog.add(app, "Affichage figé : aucune image 3 s après un appui (dernière image il y a $since)")
                watchUntilFrame(app, t)
            }
        }, 3000)
    }

    /** Mesure la durée du figeage : note quand la première image revient. */
    private fun watchUntilFrame(app: android.content.Context, since: Long) {
        val h = Handler(thread.looper)
        h.postDelayed(object : Runnable {
            override fun run() {
                if (lastFrame > since) { WakeLog.add(app, "Affichage revenu après ${(lastFrame - since) / 1000} s"); return }
                if (SystemClock.uptimeMillis() - since > 300_000) { WakeLog.add(app, "Affichage toujours figé après 5 min"); return }
                h.postDelayed(this, 1000)
            }
        }, 1000)
    }

    fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "releve-affichage.txt")

    /**
     * 90 s après chaque démarrage de la voiture : erreurs d'affichage du système (carte graphique, composition
     * de l'écran) gardées dans un fichier joint au diagnostic. Le figeage du 28/11 a eu lieu au démarrage.
     */
    fun afterWake(ctx: android.content.Context) {
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(90_000)
            val r = runCatching {
                RootAccess.run(app, "logcat -d -v time -t 3000 2>&1 | grep -iE 'OpenGLRenderer|SurfaceFlinger|hwcomposer|HWC|mali|gralloc|EGL|Choreographer|ANR|vsync|display' | tail -n 80", 20_000)
            }.getOrElse { "indisponible : ${it.message}" }
            val f = file(app)
            val head = "=== Démarrage du " + java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date(System.currentTimeMillis() - 90_000)) + "\n"
            val old = if (f.exists()) f.readText().takeLast(60_000) else ""
            f.writeText(old + head + r.trim() + "\n\n")
        }.start()
    }
}
