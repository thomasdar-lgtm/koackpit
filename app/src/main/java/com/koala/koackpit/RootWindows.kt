package com.koala.koackpit

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock

/**
 * Commandes de fenêtres (écran partagé) en administrateur, RAPIDES.
 *
 * Avant : chaque commande ouvrait l'accès système, lançait l'outil, puis refermait (~2 à 3 s), d'où les
 * transitions lentes. Maintenant l'outil reste lancé (« résident ») et répond en quelques millisecondes.
 * S'il n'est pas joignable, il est relancé une fois ; en dernier recours on revient à l'ancienne méthode.
 */
object RootWindows {
    private val lock = Any()

    private fun ask(cmd: String, timeoutMs: Int = 4000): String? = runCatching {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(RootTool.SOCKET))
            s.soTimeout = timeoutMs
            s.outputStream.write((cmd + "\n").toByteArray())
            s.inputStream.bufferedReader().readLine()
        }
    }.getOrNull()

    /**
     * Démarre l'outil résident. Il est sorti du groupe de processus d'adb (/acct/cgroup.procs) : sinon
     * Android le tue en même temps qu'adb quand l'accès système est refermé.
     */
    private fun start(ctx: Context): String {
        val uid = ctx.applicationInfo.uid
        val tool = "CLASSPATH=\$(pm path ${ctx.packageName} | head -n 1 | cut -d: -f2) exec app_process /system/bin ${RootTool::class.java.name} serve $uid"
        RootAccess.run(ctx, "setsid sh -c 'echo \$\$ > /acct/cgroup.procs 2>/dev/null; $tool' </dev/null >/dev/null 2>&1 & sleep 1; echo lancé")
        repeat(15) {
            if (ask("version", 1000) == RootTool.VERSION) return "prêt"
            Thread.sleep(200)
        }
        return "ne répond pas"
    }

    /** S'assure que l'outil résident tourne, à la bonne version. */
    private fun ensure(ctx: Context): Boolean {
        val v = ask("version", 1500)
        if (v == RootTool.VERSION) return true
        if (v != null) ask("quit", 1500)          // ancienne version (après une mise à jour) : remplacée
        val t = SystemClock.elapsedRealtime()
        val r = runCatching { start(ctx) }.getOrElse { "échec : ${it.message}" }
        WakeLog.add(ctx, "Outil fenêtres résident : $r (${SystemClock.elapsedRealtime() - t} ms)")
        return r == "prêt"
    }

    /** Exécute une commande de fenêtres (layout, dismiss, stacks). Ne jamais appeler sur le fil principal. */
    fun run(ctx: Context, vararg args: String): String = synchronized(lock) {
        val app = ctx.applicationContext
        if (ensure(app)) ask(args.joinToString(" "))?.let { return it }
        // Dernier recours : ancienne méthode (lente mais éprouvée)
        RootAccess.run(app, RootTool.command(app.packageName, *args), 20_000)
    }

    /** À l'avance (démarrage du service) : la première conduite sera rapide elle aussi. */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        Thread { synchronized(lock) { runCatching { ensure(app) } } }.start()
    }
}
