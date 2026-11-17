package com.koala.koackpit

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relevé automatique pendant chaque appel (pour comprendre « je n'entends rien » et la sonnerie trop forte) :
 * chaque seconde, l'état de l'appel, la source audio et les volumes ; à la fin, les messages du service de
 * l'autoradio (coupures du son, volume, audio) depuis le début de l'appel. Fichier : releve-appel.txt.
 * Observation seulement : rien n'est modifié.
 */
object CallLog {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var startStamp = ""
    private var lines = 0
    private val hfpNames = listOf("init", "inactif", "connexion", "connecté", "appel sortant", "appel entrant", "en appel")

    fun file(ctx: Context) = File(ctx.filesDir, "releve-appel.txt")
    private fun now(f: String) = SimpleDateFormat(f, Locale.FRANCE).format(Date())

    private fun prop(k: String) = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
    }.getOrDefault("?")

    fun onHfp(ctx: Context, state: Int) {
        val app = ctx.applicationContext
        if (state in 4..6 && !active) start(app)
        else if (state in 0..3 && active) stop(app)
    }

    private fun start(app: Context) {
        active = true; lines = 0
        startStamp = now("MM-dd HH:mm:ss.000")
        file(app).writeText("Appel du ${now("dd/MM HH:mm:ss")} (Koackpit ${BuildConfig.VERSION_NAME})\n")
        WakeLog.add(app, "Appel : relevé démarré")
        main.post(object : Runnable {
            override fun run() {
                if (!active) return
                Thread {
                    val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val hfp = CarLink.hfpState()
                    val line = "${now("HH:mm:ss")} ${hfpNames.getOrElse(hfp ?: -1) { "$hfp" }} | source ${CarLink.sourceName(CarLink.currentSource())}" +
                        " | volume autoradio ${prop("persist.car.af_master_volume")}" +
                        " | Android appel ${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}" +
                        " sonnerie ${am.getStreamVolume(AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(AudioManager.STREAM_RING)}" +
                        " média ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
                    if (lines++ < 600) runCatching { file(app).appendText(line + "\n") }
                }.start()
                main.postDelayed(this, 1000)
            }
        })
    }

    private fun stop(app: Context) {
        active = false
        val since = startStamp
        Thread {
            val r = runCatching {
                RootAccess.run(app,
                    "logcat -d -v time -t '$since' -e 'BtProxy|BtMain|AppManager|olume|Audio|audio|Hfp|hfp|Call|Mute|mute' 2>/dev/null | tail -n 150 | cut -c1-200",
                    30_000)
            }.getOrElse { "indisponible : ${it.message}" }
            runCatching { file(app).appendText("--- Messages du service pendant l'appel :\n$r\n") }
            WakeLog.add(app, "Appel : relevé enregistré (${lines} s)")
        }.start()
    }
}
