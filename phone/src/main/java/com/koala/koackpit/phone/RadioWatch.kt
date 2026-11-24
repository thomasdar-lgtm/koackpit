package com.koala.koackpit.phone

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal radio du téléphone (1.5) : ce que le TÉLÉPHONE voit au moment des coupures avec l'autoradio.
 *
 * L'autoradio ne voit qu'une « perte de liaison anormale » : il ne peut pas dire qui a lâché. Ici on note, à la
 * milliseconde, les événements Bluetooth (liaison, profils son/appel, Bluetooth allumé/éteint, avec la raison
 * quand Android la donne) et du point d'accès (arrêt, démarrage), l'écran et l'économie d'énergie. L'autoradio
 * vient lire ce journal (/journal) et le joint à son fichier de diagnostic (releve-telephone.txt).
 */
object RadioWatch {
    data class Line(val seq: Int, val text: String)
    private val lines = java.util.concurrent.CopyOnWriteArrayList<Line>()
    @Volatile var seq = 0
    private var file: File? = null
    private var registered = false
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE)

    @Synchronized fun add(text: String) {
        val s = ++seq
        val t = "${fmt.format(Date())}  $text"
        lines.add(Line(s, t)); while (lines.size > 400) lines.removeAt(0)
        runCatching {
            file?.let { f ->
                f.appendText(t + "\n")
                if (f.length() > 300_000) f.writeText(f.readLines().takeLast(1500).joinToString("\n") + "\n")
            }
        }
    }

    fun since(n: Int): List<Line> = lines.filter { it.seq > n }

    private val actions = mapOf(
        BluetoothDevice.ACTION_ACL_CONNECTED to "BT liaison établie",
        BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED to "BT déconnexion demandée",
        BluetoothDevice.ACTION_ACL_DISCONNECTED to "BT LIAISON PERDUE",
        "android.bluetooth.adapter.action.STATE_CHANGED" to "BT du téléphone",
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" to "BT son (A2DP)",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" to "BT appels (HFP)",
        "android.net.wifi.WIFI_AP_STATE_CHANGED" to "POINT D'ACCÈS",
        Intent.ACTION_SCREEN_ON to "écran allumé",
        Intent.ACTION_SCREEN_OFF to "écran éteint",
        "android.os.action.POWER_SAVE_MODE_CHANGED" to "économie d'énergie changée",
        "android.os.action.DEVICE_IDLE_MODE_CHANGED" to "veille profonde changée",
        Intent.ACTION_AIRPLANE_MODE_CHANGED to "mode avion",
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            runCatching {
                val what = actions[i.action] ?: i.action.orEmpty()
                val ex = i.extras
                val parts = ex?.keySet()?.sorted()?.mapNotNull { k ->
                    val v = runCatching { @Suppress("DEPRECATION") ex.get(k) }.getOrNull()
                    val short = k.substringAfterLast('.')
                    when (v) {
                        is BluetoothDevice -> "appareil=" + (runCatching { v.name }.getOrNull() ?: "?") + " (" + v.address.takeLast(5) + ")"
                        null -> null
                        else -> "$short=$v"
                    }
                }.orEmpty()
                val note = when (i.action) {
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> when (ex?.getInt("wifi_state", -1)) {
                        10 -> " → s'arrête"; 11 -> " → ARRÊTÉ"; 12 -> " → démarre"; 13 -> " → actif"; 14 -> " → ÉCHEC"; else -> ""
                    }
                    "android.bluetooth.adapter.action.STATE_CHANGED" -> when (ex?.getInt("android.bluetooth.adapter.extra.STATE", -1)) {
                        10 -> " → ÉTEINT"; 12 -> " → allumé"; 13 -> " → s'éteint"; 11 -> " → s'allume"; else -> ""
                    }
                    else -> ""
                }
                add("$what$note  [${parts.joinToString(", ")}]")
            }
        }
    }

    fun start(c: Context) {
        if (registered) return
        registered = true
        file = File(c.filesDir, "journal-radio.txt")
        val f = IntentFilter(); actions.keys.forEach { f.addAction(it) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else c.registerReceiver(receiver, f)
        }.onFailure { add("surveillance impossible : ${it.javaClass.simpleName}") }
        val bt = c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        add("surveillance radio démarrée (accès Bluetooth : ${if (bt) "oui" else "NON, à autoriser dans l'appli"})")
    }

    fun stop(c: Context) {
        if (!registered) return
        registered = false
        runCatching { c.unregisterReceiver(receiver) }
    }
}
