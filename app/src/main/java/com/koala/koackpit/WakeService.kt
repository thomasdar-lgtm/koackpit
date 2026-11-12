package com.koala.koackpit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Remet Koackpit au premier plan quand la voiture démarre.
 *
 * L'autoradio ne s'éteint pas au contact coupé : il se met en veille. Au contact mis, son service « car »
 * envoie android.car.action.ACC_STATE (arg_int = 1). Ce signal n'arrive qu'aux applis qui l'écoutent
 * pendant qu'elles tournent : ce service discret reste donc actif en permanence pour l'attendre.
 */
class WakeService : Service() {
    companion object {
        private const val ACTION_ACC = "android.car.action.ACC_STATE"
        private const val CHANNEL = "koackpit_service"

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WakeService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        /**
         * Affiche l'accueil Koackpit. L'autoradio remet son propre écran au réveil : on repasse devant
         * plusieurs fois pendant les premières secondes.
         */
        fun bringToFront(ctx: Context) {
            val app = ctx.applicationContext
            val h = Handler(Looper.getMainLooper())
            // Conduite au démarrage (réglage, activé par défaut) ou conduite en cours avant la veille :
            // on ouvre le GPS + lecteur côte à côte plutôt que l'accueil.
            val wantDrive = Prefs.autoDrive(app) || Prefs.driving(app)
            if (wantDrive) {
                for (delay in longArrayOf(2500, 7000)) {
                    h.postDelayed({
                        if (!MusicActivity.isOpen()) {
                            WakeLog.add(app, "Reprise de la conduite (+${delay} ms)")
                            SplitScreen.launch(app, listOf(Prefs.nav(app), Targets.WAZE, Targets.MAPS)) { r ->
                                WakeLog.add(app, r.lines().filter { it.isNotBlank() }.joinToString(" / "))
                            }
                        }
                    }, delay)
                }
                return
            }
            for (delay in longArrayOf(800, 2500, 5000)) {
                h.postDelayed({
                    if (!MainActivity.visible && !MusicActivity.isOpen()) {
                        val r = runCatching {
                            app.startActivity(Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }
                        WakeLog.add(app, "Retour au premier plan (+${delay} ms) : " + (r.exceptionOrNull()?.message ?: "demandé"))
                    }
                }, delay)
            }
        }
    }

    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isInitialStickyBroadcast) return            // ancienne valeur, pas un vrai démarrage
            val v = i.getIntExtra("arg_int", -1)
            WakeLog.add(c, "Signal contact (ACC) = $v")
            if (v != 0) bringToFront(c)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Koackpit", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Koackpit")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        WakeLog.add(this, "Service démarré (fond d'écran Koackpit : ${if (KeepAlive.active(this)) "oui" else "NON"}, " +
            "affichage par-dessus : ${if (Settings.canDrawOverlays(this)) "oui" else "non"})")
        registerReceiver(accReceiver, IntentFilter(ACTION_ACC))
        // Android 10 n'autorise une appli en arrière-plan à ouvrir un écran que si elle a le droit
        // « afficher par-dessus les autres applis » : on se l'accorde une fois via l'accès système.
        if (!Settings.canDrawOverlays(this)) {
            val app = applicationContext
            // Après 30 s : ne pas gêner une mise à jour en cours, qui utilise aussi l'accès système
            Thread {
                Thread.sleep(30_000)
                if (!Settings.canDrawOverlays(app)) runCatching { RootAccess.run(app, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow") }
            }.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(accReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Démarrage complet de l'autoradio (après une vraie extinction) : service + Koackpit au premier plan. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        WakeLog.add(ctx, "Démarrage complet de l'autoradio")
        WakeService.start(ctx)
        WakeService.bringToFront(ctx)
    }
}
