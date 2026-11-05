package com.koala.koackpit

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, APPS, SETTINGS }

class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf(Screen.HOME)
    private val upd = UpdateState()
    private val resumes = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Écran d'accueil : le bouton retour ramène à l'accueil sans fermer l'appli
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { screen.value = Screen.HOME }
        })
        setContent { Koackpit(screen, upd, resumes.value) }
    }

    /** Chaque retour sur Koackpit relance la recherche de mise à jour (au plus une fois par minute). */
    override fun onResume() {
        super.onResume()
        resumes.value++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bouton maison : retour à l'accueil
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            screen.value = Screen.HOME
            MusicActivity.closeIfOpen()
        }
    }
}

@Composable
fun Koackpit(screen: androidx.compose.runtime.MutableState<Screen>, upd: UpdateState, resumes: Int) {
    LaunchedEffect(resumes) { Updater.refresh(upd, force = false) }
    Column(Modifier.fillMaxSize().background(Navy)) {
        UpdateBanner(upd)
        when (screen.value) {
            Screen.HOME -> Home(onApps = { screen.value = Screen.APPS }, onSettings = { screen.value = Screen.SETTINGS })
            Screen.APPS -> AppDrawer(onBack = { screen.value = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(upd, onBack = { screen.value = Screen.HOME })
        }
    }
}

@Composable
fun Home(onApps: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5_000) } }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.FRANCE).format(now) }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
    }
    val nav = Prefs.nav(ctx)

    fun go(pkgs: List<String>, name: String) {
        if (!Apps.open(ctx, pkgs)) toast(ctx, "$name n'est pas installé")
    }

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Colonne gauche : heure, date
        Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(time, color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Black, lineHeight = 88.sp)
                Text(date, color = Yellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Koackpit", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                RoundButton(Icons.Filled.Settings, onSettings)
            }
        }

        // Grille de tuiles
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.weight(1f), "GPS", if (nav == Targets.MAPS) "Google Maps" else "Waze", Icons.Filled.Place, accent = true) {
                    go(listOf(nav, Targets.WAZE, Targets.MAPS), "Le GPS")
                }
                Tile(Modifier.weight(1f), "Musique", "Deezer", Icons.Filled.PlayArrow, accent = true) { go(Targets.MUSIC, "Deezer") }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.weight(1f), "Voiture", "Car Info", Icons.Filled.Info) { go(Targets.CAR_INFO, "Car Info") }
                Tile(Modifier.weight(1f), "Diagnostic", "Car Scanner", Icons.Filled.Build) { go(Targets.OBD, "Car Scanner") }
                Tile(Modifier.weight(1f), "Téléphone", "Bluetooth", Icons.Filled.Phone) { go(Targets.PHONE, "Le téléphone") }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(Modifier.weight(1f), "Radio", "FM / DAB", null, glyph = "FM") { go(Targets.RADIO + "com.ex.dabplayer.pad", "La radio") }
                Tile(Modifier.weight(2f), "Toutes les applis", null, Icons.Filled.List, onClick = onApps)
            }
        }
    }
}

@Composable
fun Tile(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    accent: Boolean = false,
    glyph: String? = null,
    onClick: () -> Unit,
) {
    val bg = if (accent) CardHi else Card
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(bg)
            .border(1.dp, if (accent) Yellow.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (accent) Yellow else Navy),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = if (accent) Navy else Yellow, modifier = Modifier.size(26.dp))
            else Text(glyph ?: "", color = Yellow, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = if (accent) 24.sp else 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Yellow, modifier = Modifier.size(26.dp)) }
}

@Composable
fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Filled.ArrowBack, onBack)
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun AppDrawer(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { mutableStateListOf<LaunchApp>() }
    LaunchedEffect(Unit) { apps.addAll(withContext(Dispatchers.IO) { Apps.list(ctx) }) }
    Column(Modifier.fillMaxSize()) {
        Header("Applis", onBack)
        LazyVerticalGrid(
            GridCells.Adaptive(110.dp),
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
        ) {
            items(apps, key = { it.pkg + "/" + it.activity }) { a ->
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable { Apps.open(ctx, a) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (a.icon != null) Image(a.icon.asImageBitmap(), null, Modifier.size(56.dp))
                    else Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Card))
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(upd: UpdateState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var nav by remember { mutableStateOf(Prefs.nav(ctx)) }
    var splitLog by remember { mutableStateOf(Prefs.splitLog(ctx)) }
    Column(Modifier.fillMaxSize()) {
        Header("Réglages", onBack)
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)) {
                Text("GPS par défaut", color = Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                for ((pkg, name) in listOf(Targets.WAZE to "Waze", Targets.MAPS to "Google Maps")) {
                    val ok = Apps.installed(ctx, pkg)
                    val on = nav == pkg
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) Yellow else Navy)
                            .clickable { nav = pkg; Prefs.setNav(ctx, pkg) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = if (on) Navy else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!ok) Text("non installé", color = if (on) Navy else Muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Conduite (GPS + lecteur BT)", color = Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                SettingButton("Lancer la conduite") {
                    Prefs.setSplitLog(ctx, "Test en cours…")
                    splitLog = "Test en cours…"
                    SplitScreen.launch(ctx, listOf(nav, Targets.WAZE, Targets.MAPS)) { r ->
                        val full = r + "\n" + SplitScreen.systemInfo(ctx)
                        Prefs.setSplitLog(ctx, full)
                        splitLog = full
                    }
                }
                if (splitLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(splitLog, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp)) {
                Text("Écran d'accueil", color = Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("Pour que le bouton maison ouvre Koackpit, choisis-le comme appli d'accueil par défaut.", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                SettingButton("Choisir l'appli d'accueil") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { toast(ctx, "Réglage indisponible : appuie sur le bouton maison et choisis Koackpit") }
                }
                Spacer(Modifier.height(16.dp))
                Text("Outils", color = Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                SettingButton("Outil de diagnostic") { ctx.startActivity(Intent(ctx, DiagActivity::class.java)) }
                Spacer(Modifier.height(8.dp))
                SettingButton("Rechercher une mise à jour") {
                    scope.launch {
                        when (Updater.refresh(upd, force = true)) {
                            null -> toast(ctx, "Pas de connexion à GitHub, vérifie le partage de connexion")
                            false -> toast(ctx, "Koackpit est à jour (${BuildConfig.VERSION_NAME})")
                            true -> toast(ctx, "Mise à jour trouvée : appuie sur Installer dans le bandeau jaune")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Koackpit ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun SettingButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navy).clickable(onClick = onClick).padding(14.dp)
    ) { Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}
