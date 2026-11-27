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

enum class Screen { HOME, APPS, SETTINGS, CAR, SLIM }

class MainActivity : ComponentActivity() {
    companion object { @Volatile var visible = false }
    private val screen = mutableStateOf(Screen.HOME)
    private val upd = UpdateHub.state
    private val resumes = mutableStateOf(0)

    private val topEdge by lazy { TopEdgeGuard(this) }
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean =
        if (topEdge.shouldIgnore(ev)) true else super.dispatchTouchEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DayNight.tick(this)   // thème jour/nuit dès l'ouverture
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
        fullScreen()
        setContent { Scaled { Koackpit(screen, upd, resumes.value) } }
    }

    /** Chaque retour sur Koackpit relance la recherche de mise à jour (au plus une fois par minute). */
    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onResume() {
        super.onResume()
        fullScreen()
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
    Box(Modifier.fillMaxSize().screenBg()) {
        Column(Modifier.fillMaxSize()) {
            if (!keepAlive) KeepAliveBanner { KeepAlive.request(ctx) }
            when (screen.value) {
                Screen.HOME -> Home(onApps = { screen.value = Screen.APPS }, onSettings = { screen.value = Screen.SETTINGS }, onCar = { screen.value = Screen.CAR })
                Screen.APPS -> AppDrawer(onBack = { screen.value = Screen.HOME })
                Screen.SETTINGS -> SettingsScreen(upd, onBack = { screen.value = Screen.HOME }, onSlim = { screen.value = Screen.SLIM })
                Screen.SLIM -> SlimScreen(onBack = { screen.value = Screen.SETTINGS })
                Screen.CAR -> CarScreen(onBack = { screen.value = Screen.HOME }, onCarInfo = {
                    if (!Apps.open(ctx, Targets.CAR_INFO)) toast(ctx, "Car Info n'est pas installé")
                })
            }
        }
        // Portes / coffre / capot ouverts : alerte en haut, au centre
        DoorAlert(CanBus.doors.value, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        // Mise à jour : petite carte en bas à droite, par-dessus l'écran
        // (la carte de mise à jour est flottante, gérée par le service : UpdateOverlay)
    }
}

@Composable
fun Home(onApps: () -> Unit, onSettings: () -> Unit, onCar: () -> Unit = {}) {
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
            onDrive = { SplitScreen.launch(ctx, listOf(nav, Targets.WAZE, Targets.MAPS), why = "appui sur Conduite (carte de l'accueil ou bouton volant)") {} },
            onMusic = { ctx.startActivity(Intent(ctx, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onPhone = { go(Targets.PHONE, "Le téléphone") },
            onCar = onCar,
            onApps = onApps,
            onSettings = onSettings,
            onDisplay = { DayNight.panel.value = !DayNight.panel.value },
            // Caméra à la demande : l'appli « Vue de face » de l'autoradio (étape 1 ; la marche arrière n'est pas touchée)
            onCamera = {
                Apps.markUserOpen(ctx)
                runCatching {
                    ctx.startActivity(Intent().setComponent(android.content.ComponentName(
                        "android.car.app.video_frontview", "android.car.app.video_frontview.FrontViewMain"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast(ctx, "Caméra introuvable") }
            },
        ),
        onGpsOnly = { go(listOf(nav, Targets.WAZE, Targets.MAPS), "Le GPS") },
        onPrev = { CarLink.prev() }, onPlay = { CarLink.playPause() }, onNext = { CarLink.next() },
        speedKmh = rememberSpeedKmh(),
        car = CanBus.info.value?.takeIf { it.fuel > 0 }?.let { i ->
            CarStats(i.fuel, FuelStats.rangeKm(ctx, i.fuel), i.outTemp.takeIf { it > -50f && it < 70f }, FuelStats.consumption(ctx).second)
        },
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
            .border(1.dp, if (accent) Yellow.copy(alpha = 0.35f) else Ink.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(16.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(if (accent) Yellow else Navy),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, null, tint = if (accent) OnAccent else AccentOnDark, modifier = Modifier.size(26.dp))
            else Text(glyph ?: "", color = AccentText, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Ink, fontSize = if (accent) 24.sp else 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).darkBtnBg(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = AccentOnDark, modifier = Modifier.size(26.dp)) }
}

@Composable
fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Filled.ArrowBack, onBack)
        Spacer(Modifier.width(16.dp))
        Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
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
                    Text(a.label, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(upd: UpdateState, onBack: () -> Unit, onSlim: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var nav by remember { mutableStateOf(Prefs.nav(ctx)) }
    var checking by remember { mutableStateOf(false) }
    var hideCallBar by remember { mutableStateOf(Prefs.hideVendorCall(ctx)) }
    var homeOn by remember { mutableStateOf(HomeMode.isOn(ctx)) }
    SettingsContent(
        homeOn = homeOn,
        onHome = { on ->
            if (on != homeOn) Thread {
                if (on) HomeMode.enable(ctx) else HomeMode.disable(ctx)
                val now = HomeMode.isOn(ctx)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    homeOn = now
                    toast(ctx, if (now) "Koackpit est maintenant le bureau" else if (on) "Activation impossible (détail dans le journal)" else "Bureau d'origine rétabli")
                }
            }.start()
        },
        hideCallBar = hideCallBar, onHideCallBar = { hideCallBar = it; Prefs.setHideVendorCall(ctx, it) },
        onPalette = { KTheme.setPalette(ctx, it) }, onAccent = { KTheme.setAccent(ctx, it) }, onStyle = { KTheme.setStyle(ctx, it) },
        onTimes = { d, n -> DayNight.setTimes(ctx, d, n); DayNight.tick(ctx) },
        nav = nav, installed = { Apps.installed(ctx, it) }, version = BuildConfig.VERSION_NAME,
        update = upd.info?.version, checking = checking,
        onNav = { nav = it; Prefs.setNav(ctx, it) },
        onCheck = {
            checking = true
            scope.launch {
                when (Updater.refresh(upd, force = true)) {
                    null -> toast(ctx, "Pas de connexion à GitHub, vérifie le partage de connexion")
                    false -> toast(ctx, "Koackpit est à jour (${BuildConfig.VERSION_NAME})")
                    true -> toast(ctx, "Nouvelle version disponible : carte en bas à droite")
                }
                checking = false
            }
        },
        onDiag = { ctx.startActivity(Intent(ctx, DiagActivity::class.java)) },
        onSlim = onSlim, onBack = onBack,
    )
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).cardBg(RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(title, color = AccentText, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun SettingButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navy).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
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
            Text("Retour automatique au démarrage", color = AccentText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text("Choisis le fond d'écran Koackpit : sinon l'autoradio ferme Koackpit pendant la veille",
                color = Ink, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Yellow).clickable(onClick = onActivate)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text("Activer", color = OnAccent, fontWeight = FontWeight.Black, fontSize = 15.sp) }
    }
}

/** Raccourci vers un favori Waze (Maison / Travail), ouvert dans la conduite. Null si Waze n'est pas le GPS choisi. */
fun wazeFav(ctx: android.content.Context, nav: String, fav: String): (() -> Unit)? {
    if (nav == Targets.MAPS) return null
    return {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("waze://?favorite=$fav&navigate=yes")).setPackage(Targets.WAZE)
        SplitScreen.launch(ctx, listOf(Targets.WAZE), i, why = "appui sur le favori Waze $fav") {}
    }
}
