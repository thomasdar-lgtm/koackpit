package com.koala.koackpit.updater

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Écran affiché pendant que Koackpit est remplacé : installe la nouvelle version, rouvre Koackpit,
 * puis disparaît. Étant une appli séparée, il reste à l'écran pendant l'arrêt de Koackpit.
 */
class UpdateActivity : Activity() {
    companion object {
        private const val KOACKPIT = "com.koala.koackpit"
        private const val TMP = "/data/local/tmp/koackpit-update.apk"
        private val NAVY = Color.parseColor("#00071A")
        private val YELLOW = Color.parseColor("#FFCC00")
        private val MUTED = Color.parseColor("#8A96B0")
        private val RED = Color.parseColor("#E63946")
    }

    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var back: Button
    private lateinit var hint: TextView
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(NAVY)
        }
        col.addView(TextView(this).apply {
            text = "Koackpit"; setTextColor(YELLOW); textSize = 44f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(YELLOW)
        }
        col.addView(spinner, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()).apply {
            topMargin = (28 * d).toInt(); bottomMargin = (20 * d).toInt()
        })
        status = TextView(this).apply {
            text = "Mise à jour en cours…"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding((40 * d).toInt(), 0, (40 * d).toInt(), 0)
        }
        col.addView(status)
        hint = TextView(this).apply {
            text = "Koackpit redémarre tout seul"; setTextColor(MUTED); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        col.addView(hint)
        back = Button(this).apply {
            text = "Revenir à Koackpit"; setTextColor(NAVY); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false; visibility = View.GONE
            background = GradientDrawable().apply { setColor(YELLOW); cornerRadius = 28 * d }
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
            setOnClickListener { openKoackpit(); finishAndRemoveTask() }
        }
        col.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (56 * d).toInt()).apply {
            topMargin = (28 * d).toInt()
        })
        setContentView(col)
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    // Pendant l'installation, le bouton retour ne fait rien (on ne laisse pas un écran à moitié mis à jour)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!running) super.onBackPressed()
    }

    private fun start(i: Intent?) {
        if (running) return
        // Koackpit a déjà ouvert l'accès système et copié l'APK à cet emplacement fixe
        if (i?.getStringExtra("tmp") != TMP) { fail("Fichier de mise à jour invalide"); return }
        running = true
        val startedAt = System.currentTimeMillis()
        Thread {
            try {
                // Accès déjà ouvert par Koackpit : connexion directe ; sinon on l'ouvre nous-mêmes
                val out = try {
                    RootShell.shell("pm install -r $TMP 2>&1; rm -f $TMP", timeoutMs = 180_000, tries = 6)
                } catch (e: IllegalStateException) {
                    RootShell.open(this)
                    RootShell.shell("pm install -r $TMP 2>&1; rm -f $TMP", timeoutMs = 180_000)
                }
                if (!out.contains("Success")) throw IllegalStateException(out.trim().take(160).ifEmpty { "aucune réponse" })
                main.post { status.text = "Redémarrage de Koackpit…" }
                // Ferme un éventuel écran « Application installée » et rouvre Koackpit
                RootShell.shell("am force-stop com.android.packageinstaller; am start -n $KOACKPIT/.MainActivity >/dev/null 2>&1")
                RootShell.close(this)
                // On laisse Koackpit s'afficher derrière avant de disparaître : aucune image intermédiaire
                main.postDelayed({ running = false; finishAndRemoveTask() }, 1500)
            } catch (e: Exception) {
                RootShell.close(this)
                // Connexion coupée pendant ou après l'installation : on vérifie si Koackpit a bien été mis à jour
                val updated = runCatching {
                    packageManager.getPackageInfo(KOACKPIT, 0).lastUpdateTime >= startedAt - 5_000
                }.getOrDefault(false)
                if (updated) main.post {
                    status.text = "Redémarrage de Koackpit…"
                    openKoackpit()
                    main.postDelayed({ running = false; finishAndRemoveTask() }, 1500)
                } else main.post { running = false; fail("Échec : ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun fail(msg: String) {
        spinner.visibility = View.GONE
        hint.visibility = View.GONE
        status.text = msg
        status.setTextColor(RED)
        back.visibility = View.VISIBLE
    }

    private fun openKoackpit() {
        runCatching {
            startActivity(Intent().setClassName(KOACKPIT, "$KOACKPIT.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
