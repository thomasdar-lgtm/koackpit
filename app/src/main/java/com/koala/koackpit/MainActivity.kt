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
    companion object { @Volatile var visible = false }
    private val screen = mutableStateOf(Screen.HOME)
    private val upd = UpdateState()
    private val resumes = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sécurité accès système : vérifiée après 20 s seulement. Juste après une mise à jour, l'appli
        // compagnon l'utilise encore pour rouvrir Koackpit : le fermer tout de suite coupait sa connexion
        // (erreur EOFException).
        run {
            val app = applicationContext
            Thread { Thread.sleep(20_000); RootAccess.ensureClosed(app) }.start()
        }
        WakeService.start(this)
        // Installe l'appli compagnon de mise à jour si elle manque (après 20 s, pour ne pas gêner un
        // redémarrage en cours après mise à jour)
        if (!UpdaterApp.ready(this)) {
            val app = applicationContext
            Thread { Thread.sleep(20_000); UpdaterApp.ensureInstalled(app) }.start()
        }
        // Écran d'accueil : le bouton retour ramène à l'accueil sans fermer l'appli
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { screen.value = Screen.HOME }
        })
        setContent { Scaled { Koackpit(screen, upd, resumes.value) } }
    }

    /** Chaque retour sur Koackpit relance la recherche de mise à jour (au plus une fois par minute). */
    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onResume() {
        super.onResume()
        visible = true
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
    // Vérification au retour sur Koackpit, puis toutes les 2 min tant que l'écran reste affiché
    LaunchedEffect(resumes) {
        Updater.refresh(upd, force = false)
        while (true) {
            delay(2 * 60_000L)
            if (MainActivity.visible) Updater.refresh(upd, force = true)
        }
    }
    val ctx = LocalContext.current
    val keepAlive = remember(resumes) { KeepAlive.active(ctx) }
    Box(Modifier.fillMaxSize().background(Navy)) {
        Column(Modifier.fillMaxSize()) {
            if (!keepAlive) KeepAliveBanner { KeepAlive.request(ctx) }
            when (screen.value) {
                Screen.HOME -> Home(onApps = { screen.value = Screen.APPS }, onSettings = { screen.value = Screen.SETTINGS })
                Screen.APPS -> AppDrawer(onBack = { screen.value = Screen.HOME })
                Screen.SETTINGS -> SettingsScreen(upd, onBack = { screen.value = Screen.HOME })
            }
        }
        // Mise à jour : petite carte en bas à droite, par-dessus l'écran
        UpdateBanner(upd, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp))
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
    val media = rememberMedia()

    fun go(pkgs: List<String>, name: String) {
        if (!Apps.open(ctx, pkgs)) toast(ctx, "$name n'est pas installé")
    }

    HomeContent(
        time = time, date = date, navName = if (nav == Targets.MAPS) "Google Maps" else "Waze", media = media,
        rail = RailActions(
            onDrive = { SplitScreen.launch(ctx, listOf(nav, Targets.WAZE, Targets.MAPS)) {} },
            onMusic = { ctx.startActivity(Intent(ctx, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onPhone = { go(Targets.PHONE, "Le téléphone") },
            onCar = { go(Targets.CAR_INFO, "Car Info") },
            onApps = onApps,
            onSettings = onSettings,
        ),
        onGpsOnly = { go(listOf(nav, Targets.WAZE, Targets.MAPS), "Le GPS") },
        onPrev = { CarLink.prev() }, onPlay = { CarLink.playPause() }, onNext = { CarLink.next() },
        guidance = NavState.guidance,
        speedKmh = rememberSpeedKmh(),
        // Favoris Maison / Travail de Waze : la conduite s'ouvre directement en guidage
        onHomeAddr = wazeFav(ctx, nav, "home"),
        onWorkAddr = wazeFav(ctx, nav, "work"),
    )
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
                var autoDrive by remember { mutableStateOf(Prefs.autoDrive(ctx)) }
                SettingButton(
                    if (autoDrive) "Au démarrage de la voiture : conduite ✓" else "Au démarrage de la voiture : accueil Koackpit"
                ) {
                    autoDrive = !autoDrive
                    Prefs.setAutoDrive(ctx, autoDrive)
                }
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
                            true -> toast(ctx, "Nouvelle version disponible : installe-la par clé USB")
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

/** Tant que le fond d'écran Koackpit n'est pas choisi, l'autoradio arrête Koackpit en veille. */
@Composable
fun KeepAliveBanner(onActivate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(CardHi).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Retour automatique au démarrage", color = Yellow, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text("Choisis le fond d'écran Koackpit : sinon l'autoradio ferme Koackpit pendant la veille",
                color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Yellow).clickable(onClick = onActivate)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text("Activer", color = Navy, fontWeight = FontWeight.Black, fontSize = 15.sp) }
    }
}

/** Raccourci vers un favori Waze (Maison / Travail), ouvert dans la conduite. Null si Waze n'est pas le GPS choisi. */
fun wazeFav(ctx: android.content.Context, nav: String, fav: String): (() -> Unit)? {
    if (nav == Targets.MAPS) return null
    return {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("waze://?favorite=$fav&navigate=yes")).setPackage(Targets.WAZE)
        SplitScreen.launch(ctx, listOf(Targets.WAZE), i) {}
    }
}
