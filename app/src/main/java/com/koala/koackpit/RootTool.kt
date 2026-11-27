package com.koala.koackpit

import android.content.ComponentName
import android.graphics.Rect

/**
 * Outil exécuté EN ADMINISTRATEUR par l'autoradio (via « app_process », comme scrcpy), pas dans l'appli.
 * Il pilote directement le gestionnaire de fenêtres d'Android 10 : placement et taille de l'écran partagé.
 * Une appli normale n'a pas ce droit ; l'administrateur (uid 0) si.
 *
 * Usage : RootTool serve <uid de Koackpit>   (outil résident, voir RootWindows)
 *         RootTool layout <largeur panneau> <largeur écran> <hauteur écran>   (pixels)
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

    /** Exécute une commande de fenêtres. Renvoie « OK … » ou « ERREUR … ». */
    fun exec(args: List<String>): String = try {
        when (args.getOrNull(0)) {
            "layout" -> {
                // GPS = fenêtre principale à gauche ; on lui donne tout sauf la largeur du panneau.
                // La taille de l'écran est fournie par l'appli : ici (hors appli), Android ne la connaît pas.
                val panelW = args[1].toInt(); val w = args[2].toInt(); val h = args[3].toInt()
                val r = Rect(0, 0, w - panelW, h)
                if (r.width() < w / 3 || h < 100) throw IllegalArgumentException("taille invalide $r (écran ${w}×$h)")
                step = "largeur $r"
                call("resizeDockedStack", r, null, null, null, null)
                "OK [$source] " + stacks()
            }
            "drive" -> {
                // Conduite en UNE étape, sans écran intermédiaire : le panneau Koackpit est déjà devant en plein
                // écran ; on ancre directement la tâche du GPS à gauche, à sa largeur finale. Le panneau passe
                // alors tout seul dans la partie droite (Android y met la fenêtre qui était au premier plan).
                val nav = args[1]; val panelW = args[2].toInt(); val w = args[3].toInt(); val h = args[4].toInt()
                val r = Rect(0, 0, w - panelW, h)
                if (r.width() < w / 3 || h < 100) throw IllegalArgumentException("taille invalide $r (écran ${w}×$h)")
                step = "recherche du GPS"
                var task: Int? = null
                val end = System.currentTimeMillis() + 4000
                while (task == null && System.currentTimeMillis() < end) { task = taskOf(nav); if (task == null) Thread.sleep(150) }
                if (task == null) throw IllegalStateException("GPS $nav non lancé")
                step = "GPS à gauche"
                try {
                    call("setTaskWindowingModeSplitScreenPrimary", task, 0, true, false, r, false)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    // Un ancien écran partagé traîne : on le referme puis on recommence
                    call("dismissSplitScreenMode", false)
                    call("setTaskWindowingModeSplitScreenPrimary", task, 0, true, false, r, false)
                }
                step = "largeur $r"
                call("resizeDockedStack", r, null, null, null, null)
                // Barre d'état de l'autoradio poussée hors de l'écran pendant la conduite : Android 9 l'impose
                // en écran partagé (au-dessus de Waze et en transparence sur le panneau). Rendue à la fin.
                val bar = args.getOrNull(5)?.toIntOrNull()?.takeIf { it in 10..120 }
                val barMsg = if (bar == null) "" else {
                    step = "barre d'état"
                    val rc = Runtime.getRuntime().exec(arrayOf("wm", "overscan", "0,-$bar,0,0")).waitFor()
                    " · barre masquée ($bar px, code $rc)"
                }
                "OK [$source] " + stacks() + barMsg
            }
            "anim" -> {
                // Vitesse des animations d'Android (ouverture/fermeture/changement de fenêtre). 1 = d'origine.
                val v = args[1].toFloat().coerceIn(0f, 1f).toString()
                step = "animations"
                for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale"))
                    Runtime.getRuntime().exec(arrayOf("settings", "put", "global", k, v)).waitFor()
                "OK"
            }
            "dismiss" -> {
                step = "fermeture"; call("dismissSplitScreenMode", false)
                val rc = Runtime.getRuntime().exec(arrayOf("wm", "overscan", "reset")).waitFor()
                "OK · barre rendue (code $rc)"
            }
            "barshow" -> "OK · barre rendue (code ${Runtime.getRuntime().exec(arrayOf("wm", "overscan", "reset")).waitFor()})"
            "stacks" -> stacks()
            // Écran au premier plan (« paquet/activité ») : sert à la surveillance du démarrage
            "top" -> {
                @Suppress("UNCHECKED_CAST")
                val t = (call("getTasks", 1) as List<Any>).firstOrNull()
                t?.let { runCatching { it.javaClass.getField("topActivity").get(it) as ComponentName? }.getOrNull()?.flattenToString() } ?: "aucun"
            }
            else -> "ERREUR commande inconnue"
        }
    } catch (t: Throwable) {
        val e = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        "ERREUR [$source, étape $step] ${e.javaClass.simpleName}: ${e.message}"
    }

    /** Nom de la prise locale de l'outil résident. */
    const val SOCKET = "koackpit_root"
    /** L'outil résident doit être de la même version que l'appli (sinon il est remplacé). */
    val VERSION = BuildConfig.VERSION_CODE.toString()

    /**
     * Outil RÉSIDENT : reste lancé en administrateur et répond instantanément aux demandes de Koackpit
     * (au lieu d'ouvrir puis refermer l'accès système à chaque fois, ~2 s). Seule l'appli Koackpit
     * (vérifiée par son numéro d'utilisateur Android) peut lui parler, et seulement pour les fenêtres.
     */
    private fun serve(allowedUid: Int) {
        val server = try { android.net.LocalServerSocket(SOCKET) } catch (e: Exception) { return }   // déjà lancé
        while (true) {
            val s = try { server.accept() } catch (e: Exception) { continue }
            try {
                if (s.peerCredentials.uid != allowedUid) continue
                s.soTimeout = 3000
                val line = s.inputStream.bufferedReader().readLine() ?: continue
                val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                val out = when (parts.firstOrNull()) {
                    "version" -> VERSION
                    "quit" -> { s.outputStream.write("OK\n".toByteArray()); s.close(); kotlin.system.exitProcess(0) }
                    else -> exec(parts)
                }
                s.outputStream.write((out.replace('\n', ' ') + "\n").toByteArray())
            } catch (e: Exception) {
            } finally {
                runCatching { s.close() }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(0) == "serve") { serve(args[1].toInt()); return }
        println(exec(args.toList()))
    }

    /** Commande shell (à lancer via RootAccess.run) qui exécute cet outil en administrateur. */
    fun command(pkg: String, vararg args: String) =
        "CLASSPATH=\$(pm path $pkg | head -n 1 | cut -d: -f2) app_process /system/bin ${RootTool::class.java.name} ${args.joinToString(" ")}"
}
