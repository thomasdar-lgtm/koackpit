package com.koala.koackpit

import android.content.Context
import android.content.Intent
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accès système de l'autoradio.
 *
 * Le système est en « ro.secure=0 / ro.adb.secure=0 » : son service adb tourne en administrateur et
 * n'exige aucune clé. Le service « car » du fabricant accepte de n'importe quelle appli une demande
 * de modification de propriété système (android.car.action.SET_SYSTEM_PROP). Koackpit s'en sert pour
 * ouvrir adb sur le réseau local, exécuter une commande, puis le refermer aussitôt.
 */
object RootAccess {
    private const val PORT = 5555
    private const val ACTION_SET_PROP = "android.car.action.SET_SYSTEM_PROP"

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private fun setProp(ctx: Context, key: String, value: String) {
        ctx.sendBroadcast(Intent(ACTION_SET_PROP).putExtra("key", key).putExtra("value", value))
    }

    private fun prop(key: String): String = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("?")

    private fun openAdb(ctx: Context) {
        setProp(ctx, "service.adb.tcp.port", PORT.toString())
        setProp(ctx, "ctl.restart", "adbd")
    }

    /** Referme adb réseau : adb reste disponible uniquement en USB, comme à l'origine. */
    fun closeAdb(ctx: Context) {
        setProp(ctx, "service.adb.tcp.port", "-1")
        setProp(ctx, "ctl.restart", "adbd")
    }

    // ---- Protocole adb minimal ----

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
        b.putInt(data.fold(0) { s, x -> s + (x.toInt() and 0xff) })
        b.putInt(cmd.inv())
        b.put(data)
        out.write(b.array()); out.flush()
    }

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    /** adb pas encore prêt (redémarrage en cours) : on peut réessayer sans risque, rien n'a été exécuté. */
    private class NotReady(cause: Exception) : Exception(cause.javaClass.simpleName, cause)

    /** Exécute une commande en administrateur. Renvoie sa sortie, ou lève une exception explicite. */
    private fun adbShell(cmd: String, timeoutMs: Int = 8000): String {
        Socket().use { s ->
            val out: OutputStream
            val inp: DataInputStream
            try {
                s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
                s.soTimeout = 5000
                out = s.getOutputStream()
                inp = DataInputStream(s.getInputStream().buffered())
                send(out, A_CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
                val hello = read(inp)
                if (hello.cmd == A_AUTH) throw IllegalStateException("adb demande une clé (accès verrouillé)")
                if (hello.cmd != A_CNXN) throw IllegalStateException("réponse adb inattendue")
            } catch (e: java.io.IOException) {
                throw NotReady(e)
            }
            s.soTimeout = timeoutMs
            var m: Msg
            send(out, A_OPEN, 1, 0, "shell:$cmd\u0000".toByteArray())
            val sb = StringBuilder()
            while (true) {
                m = read(inp)
                when (m.cmd) {
                    A_OKAY -> {}
                    A_WRTE -> { sb.append(String(m.data)); send(out, A_OKAY, 1, m.a0) }
                    A_CLSE -> { send(out, A_CLSE, 1, m.a0); return sb.toString().trim() }
                }
            }
        }
    }

    /** Ouvre adb, attend qu'il réponde, exécute, referme. Toujours refermé, même en cas d'échec. */
    fun run(ctx: Context, cmd: String, timeoutMs: Int = 8000): String {
        openAdb(ctx)
        try {
            var last: Exception? = null
            repeat(10) {
                Thread.sleep(700)
                try { return adbShell(cmd, timeoutMs) } catch (e: NotReady) { last = e }
            }
            throw IllegalStateException("adb réseau ne s'est pas ouvert (${last?.message})")
        } finally {
            closeAdb(ctx)
        }
    }

    /** Exécute une commande en réessayant tant qu'adb réseau n'est pas encore prêt. */
    private fun shellRetry(cmd: String, timeoutMs: Int = 8000): String {
        var last: Exception? = null
        repeat(10) {
            try { return adbShell(cmd, timeoutMs) } catch (e: NotReady) { last = e; Thread.sleep(700) }
        }
        throw IllegalStateException("accès système indisponible (${last?.message})")
    }

    /**
     * Mise à jour silencieuse.
     * Constat sur l'autoradio : une commande lancée en arrière-plan depuis adb est tuée dès la fin de la
     * session (le journal n'était jamais créé). pm install est donc exécuté au premier plan et on attend
     * sa réponse. Si l'installation réussit, Android arrête Koackpit pendant la validation : l'installation
     * continue côté système, et le récepteur PackageReplaced relance Koackpit.
     */
    fun silentInstall(ctx: Context, apk: File, onStep: (String) -> Unit) {
        val tmp = "/data/local/tmp/koackpit-update.apk"
        openAdb(ctx)
        try {
            onStep("copie…")
            val ls = shellRetry("cp '${apk.absolutePath}' $tmp && chmod 644 $tmp && ls -l $tmp 2>&1")
            if (!ls.contains("koackpit-update.apk") || ls.contains("No such")) throw IllegalStateException("copie impossible : ${ls.take(80)}")

            onStep("en cours (jusqu'à 1 min)…")
            // Ferme un éventuel installateur Android resté ouvert (ancienne mise à jour figée) :
            // sinon il réapparaît avec « Application installée – Ouvrir / OK » quand Koackpit est remplacé.
            val out = shellRetry(
                "am force-stop com.android.packageinstaller; logcat -c; pm install -r $tmp 2>&1; rm -f $tmp",
                timeoutMs = 180_000,
            )
            if (!out.contains("Success")) throw IllegalStateException("pm : ${out.trim().take(160)}")
            onStep("terminée, redémarrage…")
        } finally {
            closeAdb(ctx)
        }
    }

    /** Journal de la dernière tentative d'installation (pour le diagnostic). */
    fun lastInstallLog(ctx: Context): String = try {
        "--- Écrans pendant la dernière mise à jour :\n" +
            (runCatching { File(ctx.filesDir, "update-trace.txt").readText() }.getOrNull() ?: "aucune trace") +
            "\n--- Système :\n" +
            run(ctx, "dumpsys package ${ctx.packageName} | grep -m2 -E 'versionName|lastUpdateTime'")
    } catch (e: Exception) { "Lecture impossible : ${e.message}" }

    /** Sécurité : si adb réseau est resté ouvert (coupure pendant une opération), on le referme. */
    fun ensureClosed(ctx: Context) {
        val p = prop("service.adb.tcp.port")
        if (p.isNotEmpty() && p != "-1" && p != "?") closeAdb(ctx)
    }

    /** Test complet, sans rien modifier : renvoie un compte rendu lisible. */
    fun test(ctx: Context): String {
        val log = StringBuilder()
        log.append("ro.secure=${prop("ro.secure")}  ro.adb.secure=${prop("ro.adb.secure")}  adbd=${prop("init.svc.adbd")}\n")
        val su = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su").firstOrNull { File(it).exists() }
        log.append("Binaire su : ${su ?: "absent"}\n")
        try {
            val r = run(ctx, "id; getenforce")
            log.append("Commande système : $r\n")
            log.append(if (r.contains("uid=0")) "RÉSULTAT : accès administrateur OK" else "RÉSULTAT : connecté mais sans droits administrateur")
        } catch (e: Exception) {
            log.append("RÉSULTAT : échec — ${e.message}")
        }
        Thread.sleep(800)
        log.append("\nadb réseau refermé (port=${prop("service.adb.tcp.port")})")
        return log.toString()
    }
}
