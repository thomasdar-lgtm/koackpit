package com.koala.koackpit

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Allègement de l'autoradio : désactivation (réversible) des applis du fabricant inutiles à Thomas.
 * Une appli désactivée n'est pas supprimée : elle ne démarre plus et ne prend plus de mémoire ;
 * « Activée » la remet exactement comme avant. Les applis sont reconnues par leur dossier système
 * (liste relevée dans le rapport de diagnostic de l'autoradio).
 */
data class SlimCandidate(val dir: String, val label: String, val why: String, val recommended: Boolean)
data class SlimItem(val c: SlimCandidate, val pkg: String, val enabled: Boolean)

object Slim {
    // Jamais touchées : Bluetooth (carBT, carBtService), service car, CAN, caméra de recul (carVideoBackCar),
    // lanceur d'origine, réglages, son, mises à jour, GPS, Google (Waze en dépend), clavier, interface système.
    val CANDIDATES = listOf(
        SlimCandidate("carRadio-KaiGeLe", "Radio FM", "Tu écoutes tout en Bluetooth", true),
        SlimCandidate("carVideoDvd", "Lecteur DVD", "Inutile sans DVD", true),
        SlimCandidate("carVideoTv", "TV", "Pas de tuner TV branché", true),
        SlimCandidate("carMp4-ksd", "Vidéos (USB/SD)", "Lecteur vidéo du fabricant", true),
        SlimCandidate("carMusic-ksd", "Musique (USB/SD)", "Lecteur de fichiers audio du fabricant", true),
        SlimCandidate("carGallery2", "Galerie photos", "Visionneuse de photos", true),
        SlimCandidate("carFloatBall", "Bulle flottante", "Raccourci flottant du fabricant", true),
        SlimCandidate("carScreenSaver", "Économiseur d'écran", "Horloge plein écran du fabricant", true),
        SlimCandidate("Email", "E-mail", "Messagerie Android inutilisée", true),
        SlimCandidate("Calendar", "Agenda", "Agenda Android inutilisé", true),
        SlimCandidate("DeskClock", "Horloge / alarmes", "Appli d'alarmes inutilisée", true),
        SlimCandidate("SoundRecorder", "Dictaphone", "Inutilisé", true),
        SlimCandidate("ExactCalculator", "Calculatrice", "Inutilisée", true),
        SlimCandidate("EasterEgg", "Easter egg Android", "Gadget caché d'Android", true),
        SlimCandidate("Traceur", "Traceur système", "Outil de débogage", true),
        SlimCandidate("carVideoAux", "Entrée vidéo AUX", "Seulement si rien n'est branché en AUX vidéo", false),
        SlimCandidate("carVideoFrontView", "Caméra avant", "Seulement si tu n'as pas de caméra avant", false),
        SlimCandidate("carFileManagerV2-gm", "Gestionnaire de fichiers", "Utile pour les clés USB : à garder si tu t'en sers", false),
        SlimCandidate("Chrome", "Chrome", "Navigateur : à garder si tu t'en sers", false),
    )

    fun scan(ctx: Context): List<SlimItem> {
        val pm = ctx.packageManager
        val apps = runCatching { pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrDefault(emptyList())
        return CANDIDATES.mapNotNull { c ->
            apps.firstOrNull { it.sourceDir?.contains("/${c.dir}/") == true }?.let { SlimItem(c, it.packageName, it.enabled) }
        }
    }

    /** Active ou désactive (administrateur). Renvoie null si OK, sinon le message d'erreur. */
    fun set(ctx: Context, pkg: String, enable: Boolean): String? = runCatching {
        val out = RootAccess.run(ctx, if (enable) "pm enable $pkg 2>&1" else "pm disable-user --user 0 $pkg 2>&1", 20_000)
        WakeLog.add(ctx, "Allègement : $pkg " + (if (enable) "réactivée" else "désactivée") + " — ${out.trim().take(80)}")
        if (out.contains("new state")) { saveState(ctx, pkg, !enable); null } else out.trim().take(120)
    }.getOrElse { it.message ?: "erreur" }

    /** Applis désactivées par Thomas (l'autoradio les réactive à chaque redémarrage complet : constaté le 17/11). */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("allegement", Context.MODE_PRIVATE)
    private fun saveState(ctx: Context, pkg: String, disabled: Boolean) {
        val set = prefs(ctx).getStringSet("off", emptySet())!!.toMutableSet()
        if (disabled) set.add(pkg) else set.remove(pkg)
        prefs(ctx).edit().putStringSet("off", set).apply()
    }

    /** Au démarrage : redésactive les applis que l'autoradio a réactivées. */
    fun reapply(ctx: Context) {
        val off = prefs(ctx).getStringSet("off", emptySet())!!
        if (off.isEmpty()) return
        val back = scan(ctx).filter { it.enabled && it.pkg in off }
        if (back.isEmpty()) return
        val out = runCatching {
            RootAccess.run(ctx, back.joinToString("; ") { "pm disable-user --user 0 ${it.pkg} 2>&1" }, 30_000)
        }.getOrElse { "ERREUR ${it.message}" }
        val n = Regex("new state").findAll(out).count()
        WakeLog.add(ctx, "Allègement réappliqué : $n/${back.size} (${back.joinToString { it.c.label }})")
    }
}

@Composable
fun SlimScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<SlimItem>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { items.addAll(withContext(Dispatchers.IO) { Slim.scan(ctx) }) }

    fun toggle(list: List<SlimItem>, enable: Boolean) {
        if (busy != null) return
        scope.launch {
            for (it in list) {
                busy = it.pkg
                val err = withContext(Dispatchers.IO) { Slim.set(ctx, it.pkg, enable) }
                val i = items.indexOfFirst { x -> x.pkg == it.pkg }
                if (err == null && i >= 0) items[i] = it.copy(enabled = enable) else if (err != null) msg = "${it.c.label} : $err"
            }
            busy = null
            items.clear(); items.addAll(withContext(Dispatchers.IO) { Slim.scan(ctx) })
        }
    }
    SlimContent(items, busy, msg, onBack,
        onToggle = { toggle(listOf(it), !it.enabled) },
        onRecommended = { toggle(items.filter { it.c.recommended && it.enabled }, false) },
    )
}

@Composable
fun SlimContent(
    items: List<SlimItem>, busy: String?, msg: String, onBack: () -> Unit,
    onToggle: (SlimItem) -> Unit, onRecommended: () -> Unit,
) {
    val off = items.count { !it.enabled }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Card).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Retour", tint = Yellow, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Alléger l'autoradio", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    if (busy != null) "En cours : $busy…" else "$off désactivée(s) sur ${items.size} · tout reste réactivable d'un appui",
                    color = Muted, fontSize = 12.sp,
                )
            }
            Text(
                "Désactiver les recommandées", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (busy == null) Yellow else Muted)
                    .clickable(enabled = busy == null, onClick = onRecommended).padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        if (msg.isNotEmpty()) Text(msg, color = Red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (items.isEmpty()) item { Text("Recherche des applis…", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            items(items, key = { it.pkg }) { it -> SlimRow(it, busy == it.pkg) { onToggle(it) } }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun SlimRow(it: SlimItem, working: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.c.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (it.c.recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text("recommandé", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Yellow.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Text("${it.c.why} · ${it.pkg}", color = Muted, fontSize = 12.sp)
        }
        // Interrupteur : vert = activée, gris = désactivée
        val on = it.enabled
        Box(
            Modifier.width(118.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF1F7A4A) else BtnDark)
                .clickable(onClick = onClick).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (working) "…" else if (on) "Activée" else "Désactivée",
                color = if (on) Color.White else Muted, fontSize = 14.sp, fontWeight = FontWeight.Black,
            )
        }
    }
}
