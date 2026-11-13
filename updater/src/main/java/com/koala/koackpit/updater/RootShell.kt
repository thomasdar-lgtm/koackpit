package com.koala.koackpit.updater

import android.content.Context
import android.content.Intent
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Accès système de l'autoradio (même mécanisme que Koackpit) : le service « car » du fabricant
 * modifie les propriétés système à la demande, ce qui ouvre adb local, administrateur et sans clé.
 */
object RootShell {
    private const val PORT = 5555
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43
    private const val A_OKAY = 0x59414b4f

    private fun setProp(ctx: Context, key: String, value: String) =
        ctx.sendBroadcast(Intent("android.car.action.SET_SYSTEM_PROP").putExtra("key", key).putExtra("value", value))

    fun open(ctx: Context) {
        setProp(ctx, "service.adb.tcp.port", PORT.toString())
        setProp(ctx, "ctl.restart", "adbd")
    }

    fun close(ctx: Context) {
        setProp(ctx, "service.adb.tcp.port", "-1")
        setProp(ctx, "ctl.restart", "adbd")
    }

    private class NotReady(e: Exception) : Exception(e)
    private class Msg(val cmd: Int, val a0: Int, val data: ByteArray)

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
        b.putInt(data.fold(0) { s, x -> s + (x.toInt() and 0xff) })
        b.putInt(cmd.inv())
        b.put(data)
        out.write(b.array()); out.flush()
    }

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; b.int; val len = b.int
        val data = ByteArray(len); inp.readFully(data)
        return Msg(cmd, a0, data)
    }

    private fun shellOnce(cmd: String, timeoutMs: Int): String {
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
                if (hello.cmd == A_AUTH) throw IllegalStateException("accès système verrouillé")
                if (hello.cmd != A_CNXN) throw IllegalStateException("réponse système inattendue")
            } catch (e: IOException) {
                throw NotReady(e)
            }
            s.soTimeout = timeoutMs
            send(out, A_OPEN, 1, 0, "shell:$cmd\u0000".toByteArray())
            val sb = StringBuilder()
            while (true) {
                val m = read(inp)
                when (m.cmd) {
                    A_OKAY -> {}
                    A_WRTE -> { sb.append(String(m.data)); send(out, A_OKAY, 1, m.a0) }
                    A_CLSE -> { send(out, A_CLSE, 1, m.a0); return sb.toString().trim() }
                }
            }
        }
    }

    /** Exécute une commande administrateur (adb doit avoir été ouvert), en attendant qu'il soit prêt. */
    fun shell(cmd: String, timeoutMs: Int = 8000, tries: Int = 15): String {
        var last: Throwable? = null
        repeat(tries) {
            try { return shellOnce(cmd, timeoutMs) } catch (e: NotReady) { last = e.cause ?: e; Thread.sleep(700) }
        }
        // Message précis (type d'erreur réseau) pour diagnostiquer au lieu de deviner
        throw IllegalStateException("accès système indisponible (${last?.javaClass?.simpleName}: ${last?.message})")
    }
}
