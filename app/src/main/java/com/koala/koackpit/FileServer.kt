package com.koala.koackpit

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Mini serveur web : le S25, connecté au même Wi-Fi, télécharge le zip depuis Firefox. */
class FileServer(private val file: () -> File?) {
    private var sock: ServerSocket? = null
    var port = 0
        private set

    fun start(): Int {
        sock?.let { return port }
        for (p in listOf(8080, 8181, 8888)) {
            val s = runCatching { ServerSocket(p) }.getOrNull() ?: continue
            sock = s
            port = p
            thread(isDaemon = true, name = "koackpit-http") {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
                }
            }
            return p
        }
        return 0
    }

    fun stop() {
        runCatching { sock?.close() }
        sock = null
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15000
        val inp = c.getInputStream().bufferedReader()
        val req = inp.readLine() ?: return
        while (true) {
            val l = inp.readLine() ?: break
            if (l.isEmpty()) break
        }
        val path = req.split(" ").getOrNull(1) ?: "/"
        val out = c.getOutputStream()
        val f = file()
        if (path.startsWith("/${Diag.ZIP_NAME}") && f != null && f.exists()) {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"${f.name}\"\r\nConnection: close\r\n\r\n").toByteArray()
            )
            f.inputStream().use { it.copyTo(out, 128 * 1024) }
        } else {
            val body = if (f != null && f.exists())
                "<p style='font-size:22px'><a href='/${Diag.ZIP_NAME}'>Télécharger ${f.name} (${f.length() / 1_048_576} Mo)</a></p>"
            else "<p style='font-size:22px'>Le fichier n'est pas encore prêt sur l'autoradio.</p>"
            val html = "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
                "<body style='font-family:sans-serif;background:#00071a;color:#fff;padding:24px'>" +
                "<h2 style='color:#ffcc00'>Koackpit</h2>$body</body>"
            val bytes = html.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        out.flush()
    }
}
