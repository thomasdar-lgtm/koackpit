package com.koala.koackpit

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relevé automatique des coupures Bluetooth / Wi-Fi (depuis 0.17.6) : releve-coupure.txt, joint au diagnostic.
 * Constaté le 23/11 : Bluetooth ET Wi-Fi perdus à la même seconde, revenus seuls 9 s et 19 s après, sans aucune
 * trace côté Koackpit. Pour savoir qui coupe (téléphone ou puce radio de l'autoradio, qui gère les deux), on garde
 * à chaque coupure les messages du système (noyau + Wi-Fi/Bluetooth) des minutes précédentes.
 * Une capture par minute au plus ; les ~100 derniers Ko sont gardés.
 */
object CutLog {
    fun file(ctx: Context) = File(ctx.filesDir, "releve-coupure.txt")
    @Volatile private var last = 0L

    fun capture(ctx: Context, what: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - last < 60_000) return
        last = now
        val app = ctx.applicationContext
        Thread {
            Thread.sleep(4000)   // laisser le système écrire ce qu'il sait de la coupure
            val out = runCatching {
                RootAccess.run(
                    app,
                    "echo '--- noyau'; dmesg 2>/dev/null | tail -n 80 | cut -c1-200; " +
                        "echo '--- système'; logcat -d -v time -t 600 -e 'wlan|WLAN|wifi|Wifi|WiFi|wpa_supplicant|supplicant|DISCONNECT|deauth|Bluetooth|bt_|hci|BtProxy|BtMain|rtk|dhd|sdio|reason=' 2>/dev/null | tail -n 160 | cut -c1-220",
                    10_000,
                )
            }.getOrElse { "relevé impossible : ${it.message}" }
            val f = file(app)
            val head = "===== ${SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())} — $what\n"
            f.appendText(head + out + "\n")
            if (f.length() > 150_000) f.writeText(f.readText().takeLast(100_000))   // garder les dernières coupures
            WakeLog.add(app, "Coupure relevée ($what) : détails joints au diagnostic")
        }.start()
    }
}
