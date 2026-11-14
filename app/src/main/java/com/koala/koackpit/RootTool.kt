package com.koala.koackpit

import android.content.ComponentName
import android.graphics.Rect

/**
 * Outil exécuté EN ADMINISTRATEUR par l'autoradio (via « app_process », comme scrcpy), pas dans l'appli.
 * Il pilote directement le gestionnaire de fenêtres d'Android 10 : placement et taille de l'écran partagé.
 * Une appli normale n'a pas ce droit ; l'administrateur (uid 0) si.
 *
 * Usage : RootTool layout <largeur panneau> <largeur écran> <hauteur écran>   (pixels)
 *         RootTool dismiss
 *         RootTool stacks
 */
object RootTool {
    private const val WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4
    private const val CREATE_MODE_RIGHT = 1   // SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT

    /** Nom de la porte d'entrée trouvée (pour le journal). */
    private var source = "?"
    private var step = "démarrage"

    /**
     * Gestionnaire de fenêtres. Cet autoradio n'a pas la classe ActivityTaskManager (constaté : ClassNotFound) :
     * on essaie donc toutes les portes d'entrée connues d'Android 9/10 et on garde la première qui sait
     * gérer l'écran partagé.
     */
    private val atm: Any by lazy {
        fun sm(name: String, stub: String): Any? {
            val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
                ?: return null
            return Class.forName(stub).getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
        }
        val candidates: List<Pair<String, () -> Any?>> = listOf(
            "ActivityTaskManager" to { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null) },
            "activity_task" to { sm("activity_task", "android.app.IActivityTaskManager\$Stub") },
            "ActivityManager" to { Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null) },
            "ActivityManagerNative" to { Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null) },
            "activity" to { sm("activity", "android.app.IActivityManager\$Stub") },
        )
        val tried = StringBuilder()
        for ((name, get) in candidates) {
            val s = runCatching { get() }.getOrNull()
            if (s == null) { tried.append("$name:absent "); continue }
            if (s.javaClass.methods.any { it.name == "setTaskWindowingModeSplitScreenPrimary" }) { source = name; return@lazy s }
            tried.append("$name:incomplet ")
        }
        throw IllegalStateException("aucun gestionnaire utilisable ($tried)")
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
            // Android 10 : « taskId » ; Android 9 (cet autoradio) : « id » ou « persistentId »
            if (top?.packageName == pkg || base?.packageName == pkg)
                return listOf("taskId", "id", "persistentId").firstNotNullOfOrNull { f -> runCatching { c.getField(f).getInt(t) }.getOrNull() }
                    ?: throw NoSuchFieldException("numéro de tâche (${c.fields.joinToString(",") { it.name }})")
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
                    // GPS = fenêtre principale à gauche ; on lui donne tout sauf la largeur du panneau.
                    // La taille de l'écran est fournie par l'appli : ici (hors appli), Android ne la connaît pas
                    // (elle valait 0 × 0, d'où un GPS plein écran et un panneau de 1 pixel).
                    val panelW = args[1].toInt(); val w = args[2].toInt(); val h = args[3].toInt()
                    val r = Rect(0, 0, w - panelW, h)
                    if (r.width() < w / 3 || h < 100) throw IllegalArgumentException("taille invalide $r (écran ${w}×$h)")
                    step = "largeur $r"
                    call("resizeDockedStack", r, null, null, null, null)
                    println("OK [$source] " + stacks())
                }
                "dismiss" -> { step = "fermeture"; call("dismissSplitScreenMode", false); println("OK") }
                "stacks" -> println(stacks())
                else -> println("ERREUR commande inconnue")
            }
        } catch (t: Throwable) {
            val e = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            println("ERREUR [$source, étape $step] ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Commande shell (à lancer via RootAccess.run) qui exécute cet outil en administrateur. */
    fun command(pkg: String, vararg args: String) =
        "CLASSPATH=\$(pm path $pkg | head -n 1 | cut -d: -f2) app_process /system/bin ${RootTool::class.java.name} ${args.joinToString(" ")}"
}
