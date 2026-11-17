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
        /** Relance la conduite (GPS + lecteur) après un délai, si elle n'est pas déjà ouverte. */
        private val relaunches = ArrayDeque<Long>()

        fun relaunchDriving(ctx: Context, delay: Long) {
            val app = ctx.applicationContext
            // Garde-fou : au plus 3 relances par minute (jamais de boucle si Android refuse l'écran partagé)
            val now = android.os.SystemClock.elapsedRealtime()
            while (relaunches.isNotEmpty() && now - relaunches.first() > 60_000) relaunches.removeFirst()
            if (relaunches.size >= 3) { WakeLog.add(app, "Relance abandonnée (trop de tentatives)"); return }
            relaunches.addLast(now)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!MusicActivity.isOpen() && Prefs.driving(app)) {
                    SplitScreen.launch(app, listOf(Prefs.nav(app), Targets.WAZE, Targets.MAPS)) { r ->
                        WakeLog.add(app, "Relance : " + r.lines().filter { it.isNotBlank() }.joinToString(" / "))
                    }
                }
            }, delay)
        }

        /**
         * Remet la source BT si besoin, en vérifiant le résultat (jusqu'à 4 essais espacés de 3 s) :
         * 1er essai par la demande directe au service (rien à l'écran), ensuite par la page Bluetooth d'origine
         * (comme quand Thomas l'ouvre à la main), puis retour à Koackpit.
         */
        fun ensureBtSource(app: Context, attempt: Int, toggle: Boolean = false) {
            val h = Handler(Looper.getMainLooper())
            Thread {
                val src = CarLink.currentSource()
                h.post {
                    if (src == CarLink.SRC_BT && attempt == 1 && toggle) {
                        // Déjà « BT » côté service, mais le module Bluetooth a pu recouper le son en se réinitialisant
                        // sans que le service le sache : on sort puis on rentre dans la source pour qu'il le rouvre.
                        WakeLog.add(app, "Son Bluetooth : source rechargée (Android puis Bluetooth)")
                        Thread { CarLink.enterSource(CarLink.SRC_ANDROID); Thread.sleep(700); CarLink.enterSource(CarLink.SRC_BT) }.start()
                        h.postDelayed({ ensureBtSource(app, 2) }, 3000)
                        return@post
                    }
                    if (src == CarLink.SRC_BT) {
                        WakeLog.add(app, if (attempt == 1) "Son Bluetooth : source déjà Bluetooth" else "Son Bluetooth : source Bluetooth OK")
                        return@post
                    }
                    if (attempt > 4) { WakeLog.add(app, "Son Bluetooth : source impossible (reste ${CarLink.sourceName(src)})"); return@post }
                    if (attempt == 1) {
                        WakeLog.add(app, "Son Bluetooth : source ${CarLink.sourceName(src)} → Bluetooth (demande directe)")
                        Thread { CarLink.enterSource(CarLink.SRC_BT) }.start()
                    } else {
                        WakeLog.add(app, "Son Bluetooth : source ${CarLink.sourceName(src)} → Bluetooth (page Bluetooth, essai $attempt)")
                        runCatching {
                            app.startActivity(Intent().setComponent(SplitScreen.BT_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { WakeLog.add(app, "Page Bluetooth refusée : ${it.message}") }
                        // Retour à Koackpit (accueil, ou conduite si elle est ouverte)
                        h.postDelayed({
                            if (MusicActivity.isOpen()) MusicActivity.restart()
                            else app.startActivity(Intent(app, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }, 1500)
                    }
                    h.postDelayed({ ensureBtSource(app, attempt + 1) }, 3000)
                }
            }.start()
        }

        fun bringToFront(ctx: Context) {
            val app = ctx.applicationContext
            val h = Handler(Looper.getMainLooper())
            // Conduite au démarrage (réglage, activé par défaut) ou conduite en cours avant la veille :
            // on ouvre le GPS + lecteur côte à côte plutôt que l'accueil.
            // Au démarrage : l'accueil Koackpit, sauf si le réglage « conduite au démarrage » est activé
            // Son Bluetooth : le service de l'autoradio coupe le son du téléphone tant que sa source n'est pas
            // « BT » (constaté au journal : au réveil la source reste « None » et le son reste coupé ; il ne revient
            // qu'à l'entrée dans la source BT). On la remet donc, une fois l'autoradio réellement réveillé.
            // Pas trop tôt : le module Bluetooth se réinitialise ~9 s après le réveil et recoupe alors le son.
            // Le vrai déclencheur est la connexion du téléphone (~30 s, voir btReceiver) ; ceci est le filet de
            // sécurité si le téléphone est resté connecté pendant une courte coupure du contact.
            // (géré par la surveillance btWatch du service, qui survit aux redémarrages de Koackpit)
            val switchBt = false
            val wantDrive = Prefs.autoDrive(app)
            if (wantDrive) {
                for (delay in longArrayOf(2500, 7000)) {
                    h.postDelayed({
                        if (!MusicActivity.isOpen()) {
                            WakeLog.add(app, "Reprise de la conduite (+${delay} ms)")
                            SplitScreen.launch(app, listOf(Prefs.nav(app), Targets.WAZE, Targets.MAPS)) { r ->
                                WakeLog.add(app, r.lines().filter { it.isNotBlank() }.joinToString(" / "))
                                h.postDelayed({ WakeLog.addWindows(app, "après reprise") }, 3000)
                            }
                        }
                    }, delay)
                }
                return
            }
            // Accueil voulu : une conduite restée ouverte avant l'arrêt de la voiture n'est PAS reprise
            // (sinon elle se relançait d'elle-même au démarrage).
            if (Prefs.driving(app) || MusicActivity.isOpen()) {
                WakeLog.add(app, "Démarrage : conduite précédente fermée, accueil Koackpit")
                Prefs.setDriving(app, false)
                MusicActivity.closeIfOpen()
            }
            // Koackpit repasse devant ensuite (plus tard si la page Bluetooth doit d'abord basculer la source)
            for (delay in if (switchBt) longArrayOf(1500, 3000, 5000) else longArrayOf(800, 2500, 5000)) {
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

    /**
     * Journal du Bluetooth de l'autoradio (pour les déconnexions « au hasard ») : chaque changement d'état
     * annoncé par le service de l'autoradio est noté avec l'heure, à côté des actions de Koackpit.
     */
    private val btReceiver = object : BroadcastReceiver() {
        private val hfp = listOf("init", "inactif", "connexion…", "connecté", "appel sortant", "appel entrant", "en appel")
        override fun onReceive(c: Context, i: Intent) {
            if (isInitialStickyBroadcast) return
            val msg = when (i.action) {
                "android.car.action.BT_HFP_STATE_CHANGED" -> {
                    val cur = i.getIntExtra("current_state", -1); val last = i.getIntExtra("last_state", -1)
                    CallLog.onHfp(c, cur)   // relevé automatique pendant les appels
                    // Module Bluetooth prêt (init → inactif) : on lui demande tout de suite de se reconnecter au
                    // téléphone, sans attendre sa propre reconnexion automatique (~20 s constatées). Second essai à +8 s.
                    if (last == 0 && cur == 1) for (d in longArrayOf(500, 8000)) main.postDelayed({
                        Thread {
                            val st = CarLink.hfpState()
                            if (st == null || st == 1) {
                                val ok = CarLink.btConnectLast()
                                WakeLog.add(c, "Bluetooth : connexion au téléphone demandée (+${d} ms) " + if (ok) "" else "— refusée")
                            }
                        }.start()
                    }, d)
                    "Bluetooth : ${hfp.getOrElse(last) { "$last" }} → ${hfp.getOrElse(cur) { "$cur" }}"
                }
                "android.car.action.BT_CONNECT_STATE" -> {
                    val on = i.getBooleanExtra("bt_connected", false)
                    // Le téléphone allume son partage de connexion à ce moment (routine Samsung) : on cherche le
                    // réseau toutes les 3 s au lieu d'attendre la recherche espacée d'Android (jusqu'à ~1 min).
                    if (on) kickWifi()
                    // Téléphone connecté : c'est le bon moment pour (re)ouvrir le son Bluetooth
                    "Bluetooth : " + (if (on) "téléphone connecté" else "TÉLÉPHONE DÉCONNECTÉ")
                }
                else -> "Bluetooth : alimentation " + (i.extras?.keySet()?.joinToString { k -> "$k=${i.extras?.get(k)}" } ?: "")
            }
            WakeLog.add(c, msg)
        }
    }

    /**
     * Recherche de mise à jour toutes les 90 s quand l'écran est allumé, quel que soit l'écran affiché :
     * la petite carte apparaît sans avoir à quitter puis revenir sur Koackpit.
     */
    private val updWatch = object : Runnable {
        override fun run() {
            val awake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            if (awake) Thread { runCatching { kotlinx.coroutines.runBlocking { Updater.refresh(UpdateHub.state, force = true) } } }.start()
            main.postDelayed(this, 90_000)
        }
    }

    private val overlayTick = object : Runnable {
        override fun run() {
            runCatching { UpdateOverlay.tick(this@WakeService) }
            main.postDelayed(this, 1000)
        }
    }

    /** Surveillance du son Bluetooth (toutes les 3 s), voir [BtSound]. */
    private val btWatch = object : Runnable {
        override fun run() {
            BtSound.check(this@WakeService)
            main.postDelayed(this, 3000)
        }
    }

    private val accReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isInitialStickyBroadcast) return            // ancienne valeur, pas un vrai démarrage
            val v = i.getIntExtra("arg_int", -1)
            WakeLog.add(c, "Signal contact (ACC) = $v")
            if (v == 0) BtSound.markNeeded(c, "contact coupé")
            if (v != 0) Handler(Looper.getMainLooper()).postDelayed({ WakeLog.addWindows(c, "réveil +1 s") }, 1000)
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
        registerReceiver(btReceiver, IntentFilter().apply {
            addAction("android.car.action.BT_HFP_STATE_CHANGED")
            addAction("android.car.action.BT_CONNECT_STATE")
            addAction("android.car.action.BT_POWER_STATE_CHANGED")
        })
        watchWifi()
        unthrottleWifiScan()
        // L'autoradio réactive au redémarrage les applis allégées (FM, DVD…) : on les redésactive (après 50 s,
        // pour ne pas gêner une mise à jour ni les autres accès système du démarrage)
        Thread { Thread.sleep(50_000); runCatching { Slim.reapply(applicationContext) } }.start()
        main.postDelayed(reverseWatch, 3000)
        main.postDelayed(btWatch, 5000)
        main.postDelayed(updWatch, 60_000)
        main.post(overlayTick)
        main.postDelayed(sourceWatch, 4000)
        // Données de la voiture (vitesse…) lues sur le bus CAN
        main.postDelayed({ CanBus.start(this) }, 2000)
        // Appli compagnon du téléphone (pochette, morceau en cours)
        PhoneLink.start(this)
        // Outil fenêtres résident lancé à l'avance (la conduite s'ouvre et se ferme alors sans attente)
        main.postDelayed({ RootWindows.warmUp(this) }, 25_000)
        // Android 10 n'autorise une appli en arrière-plan à ouvrir un écran que si elle a le droit
        // « afficher par-dessus les autres applis » : on se l'accorde une fois via l'accès système.
        if (!Settings.canDrawOverlays(this) || NavPermissions.missing(this)) {
            val app = applicationContext
            // Après 30 s : ne pas gêner une mise à jour en cours, qui utilise aussi l'accès système
            Thread {
                Thread.sleep(30_000)
                if (!Settings.canDrawOverlays(app) || NavPermissions.missing(app)) runCatching {
                    RootAccess.run(app, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow; " + NavPermissions.grantCommand(app))
                    WakeLog.add(app, "Autorisations accordées (affichage, position, guidage)")
                }
            }.start()
        }
    }

    /**
     * Surveillance de la caméra de recul pendant la conduite (1 lecture par seconde, uniquement écran
     * allumé et conduite active). À la fin de la marche arrière, si l'écran partagé n'est pas revenu
     * en 3 s, on le rétablit.
     */
    private val main = Handler(Looper.getMainLooper())
    private var lastBack = false
    /** Journal de la source audio de l'autoradio : chaque changement est noté (Bluetooth, Android, Radio…). */
    private var lastSource: Int? = null
    private val sourceWatch = object : Runnable {
        override fun run() {
            Thread {
                val s = CarLink.currentSource()
                main.post {
                    if (s != null && s != lastSource) {
                        if (lastSource != null) WakeLog.add(this@WakeService, "Source audio : ${CarLink.sourceName(lastSource)} → ${CarLink.sourceName(s)}")
                        else WakeLog.add(this@WakeService, "Source audio : ${CarLink.sourceName(s)}")
                        lastSource = s
                    }
                }
            }.start()
            main.postDelayed(this, 2000)
        }
    }

    private val reverseWatch = object : Runnable {
        override fun run() {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (Prefs.driving(this@WakeService) && pm.isInteractive) {
                Thread {
                    val back = CarLink.inBackCar() ?: false
                    main.post {
                        if (back != lastBack) {
                            WakeLog.add(this@WakeService, if (back) "Marche arrière : caméra" else "Fin de marche arrière")
                            if (!back) main.postDelayed({
                                if (Prefs.driving(this@WakeService) && !MusicActivity.shown) {
                                    WakeLog.add(this@WakeService, "Écran partagé non revenu : rétablissement")
                                    if (MusicActivity.isOpen()) MusicActivity.restart()   // onDestroy relance
                                    else relaunchDriving(this@WakeService, 0)
                                }
                            }, 3000)
                        }
                        lastBack = back
                    }
                }.start()
            }
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Journal du Wi-Fi (partage de connexion du téléphone) : chaque perte et chaque retour sont notés,
     * avec le nom du réseau, le signal juste avant la perte et la durée de la coupure.
     */
    private var wifiCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var wifiUpAt = 0L
    private var wifiDownAt = 0L
    private var wifiRssi: Int? = null
    private fun watchWifi() = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        fun mins(ms: Long) = if (ms < 120_000) "${ms / 1000} s" else "${ms / 60_000} min"
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val info = runCatching { wm.connectionInfo }.getOrNull()
                val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "?"
                val now = android.os.SystemClock.elapsedRealtime()
                val gap = if (wifiDownAt > 0) ", coupure ${mins(now - wifiDownAt)}" else ""
                wifiUpAt = now; wifiRssi = info?.rssi; wifiOn = true
                val kick = if (kickStart > 0) ", ${(now - kickStart) / 1000} s après le téléphone" else ""
                kickStart = 0
                main.post { WakeLog.add(this@WakeService, "Wi-Fi connecté ($ssid, signal ${info?.rssi ?: "?"} dBm$gap$kick)") }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                runCatching { wm.connectionInfo?.rssi }.getOrNull()?.takeIf { it > -127 }?.let { wifiRssi = it }
            }
            override fun onLost(network: android.net.Network) {
                val now = android.os.SystemClock.elapsedRealtime()
                val dur = if (wifiUpAt > 0) " après ${mins(now - wifiUpAt)}" else ""
                val screen = if ((getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) "écran allumé" else "écran éteint"
                wifiDownAt = now; wifiOn = false
                main.post { WakeLog.add(this@WakeService, "Wi-Fi PERDU$dur (dernier signal ${wifiRssi ?: "?"} dBm, $screen)") }
            }
        }
        cm.registerNetworkCallback(
            android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        wifiCb = cb
    }

    @Volatile private var wifiOn = false
    private var kickStart = 0L
    /** Recherche rapide du partage de connexion : toutes les 3 s pendant 90 s, tant que le Wi-Fi n'est pas connecté. */
    private fun kickWifi() {
        if (wifiOn || kickStart > 0) return
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        kickStart = android.os.SystemClock.elapsedRealtime()
        WakeLog.add(this, "Wi-Fi : recherche rapide du partage de connexion")
        val t0 = kickStart
        val tick = object : Runnable {
            override fun run() {
                if (wifiOn || kickStart != t0) return
                if (android.os.SystemClock.elapsedRealtime() - t0 > 90_000) {
                    WakeLog.add(this@WakeService, "Wi-Fi : partage introuvable après 90 s"); kickStart = 0; return
                }
                runCatching { if (!wm.isWifiEnabled) wm.isWifiEnabled = true }
                @Suppress("DEPRECATION") runCatching { wm.startScan(); wm.reconnect() }
                main.postDelayed(this, 3000)
            }
        }
        main.post(tick)
    }

    /** Android limite les recherches Wi-Fi des applis (4 par 2 min) : on lève cette limite une fois. */
    private fun unthrottleWifiScan() {
        val v = runCatching { Settings.Global.getString(contentResolver, "wifi_scan_throttle_enabled") }.getOrNull()
        if (v == "0") return
        val app = applicationContext
        Thread {
            Thread.sleep(40_000)
            runCatching {
                RootAccess.run(app, "settings put global wifi_scan_throttle_enabled 0")
                WakeLog.add(app, "Wi-Fi : limite de recherche levée")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(accReceiver) }
        runCatching { unregisterReceiver(btReceiver) }
        wifiCb?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        main.removeCallbacks(reverseWatch)
        main.removeCallbacks(btWatch)
        main.removeCallbacks(updWatch)
        main.removeCallbacks(overlayTick)
        UpdateOverlay.hide(this)
        main.removeCallbacks(sourceWatch)
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
