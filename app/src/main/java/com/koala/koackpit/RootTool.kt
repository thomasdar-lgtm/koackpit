package com.koala.koackpit

import android.content.ComponentName
import android.graphics.Rect

/**
 * Outil exécuté EN ADMINISTRATEUR par l'autoradio (via « app_process », comme scrcpy), pas dans l'appli.
 * Il pilote directement le gestionnaire de fenêtres d'Android 10 : placement et taille de l'écran partagé.
 * Une appli normale n'a pas ce droit ; l'administrateur (uid 0) si.
 *
 * Usage : RootTool layout <paquet panneau> <paquet GPS> <largeur panneau en pixels>
 *         RootTool dismiss
 *         RootTool stacks
 */
object RootTool {
    private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
    private const val CREATE_MODE_RIGHT = 1   // SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT

    private val atm: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    /** Appel par nom et nombre de paramètres (résiste aux petites variations du fabricant). */
    private fun call(name: String, vararg args: Any?): Any? {
        val m = atm.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException(name)
        return m.invoke(atm, *args)
    }

    private fun taskOf(pkg: String): Int? {
        @Suppress("UNCHECKED_CAST")
        val tasks = call("getTasks", 30) as List<Any>
        for (t in tasks) {
            val c = t.javaClass
            val top = runCatching { c.getField("topActivity").get(t) as ComponentName? }.getOrNull()
            val base = runCatching { c.getField("baseActivity").get(t) as ComponentName? }.getOrNull()
            if (top?.packageName == pkg || base?.packageName == pkg) return c.getField("taskId").getInt(t)
        }
        return null
    }

    private fun stacks(): String = runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = call("getAllStackInfos") as List<Any>
        list.mapNotNull { s ->
            val c = s.javaClass
            val names = runCatching { (c.getField("taskNames").get(s) as Array<*>).joinToString(",") { it.toString().substringBefore('/') } }.getOrDefault("")
            val bounds = runCatching { (c.getField("bounds").get(s) as Rect).toShortString() }.getOrDefault("?")
            val visible = runCatching { c.getField("visible").getBoolean(s) }.getOrDefault(true)
            if (visible && names.isNotEmpty()) "$names $bounds" else null
        }.joinToString(" | ")
    }.getOrElse { "?" }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.getOrNull(0)) {
                "layout" -> {
                    val panelPkg = args[1]; val navPkg = args[2]; val panelW = args[3].toInt()
                    val panel = taskOf(panelPkg) ?: throw IllegalStateException("panneau introuvable")
                    val nav = taskOf(navPkg) ?: throw IllegalStateException("GPS introuvable")
                    // Panneau Koackpit = fenêtre principale ancrée à DROITE ; le GPS prend le reste à gauche.
                    call("setTaskWindowingModeSplitScreenPrimary", panel, CREATE_MODE_RIGHT, true, false, null, false)
                    call("setTaskWindowingMode", nav, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, true)
                    val dm = Class.forName("android.content.res.Resources").getMethod("getSystem").invoke(null)
                        .let { it!!.javaClass.getMethod("getDisplayMetrics").invoke(it) as android.util.DisplayMetrics }
                    val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
                    call("resizeDockedStack", Rect(w - panelW, 0, w, h), null, null, null, null)
                    println("OK " + stacks())
                }
                "dismiss" -> { call("dismissSplitScreenMode", false); println("OK") }
                "stacks" -> println(stacks())
                else -> println("ERREUR commande inconnue")
            }
        } catch (t: Throwable) {
            val e = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("ERREUR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Commande shell (à lancer via RootAccess.run) qui exécute cet outil en administrateur. */
    fun command(pkg: String, vararg args: String) =
        "CLASSPATH=\$(pm path $pkg | head -n 1 | cut -d: -f2) app_process /system/bin ${RootTool::class.java.name} ${args.joinToString(" ")}"
}
