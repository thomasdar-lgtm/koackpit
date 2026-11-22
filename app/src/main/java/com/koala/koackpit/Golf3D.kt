package com.koala.koackpit

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La Golf 5 en 3D, dessinée par Filament (moteur 3D libre de Google, intégré à l'appli, hors ligne, aucune
 * connexion) directement par la puce graphique de l'autoradio.
 *
 * Avant (jusqu'à 0.16.7) : page web three.js. Le moteur web de l'autoradio (Chrome 66, 2018) n'affiche pas la 3D :
 * il fallait convertir chaque image en photo → lent et saccadé.
 * Modèle : assets/golf/golf.glb, exporté de la page web (web/golf3d, build-native.sh) avec les pneus détaillés,
 * le compartiment moteur, le coffre et la peinture « Ma Golf » ; golf.json : fiches des pièces, vue de départ.
 * Lumière d'ambiance : assets/golf/ibl.ktx (environnement studio d'exemple de Filament, licence Apache 2.0).
 *
 * Ouvrants : suivent la trame CAN « portes » ([Doors]). Un doigt : tourner ; deux : zoomer ; appui : fiche.
 * Image redessinée seulement quand quelque chose bouge (rien à l'arrêt). En cas d'échec : photo de la Golf.
 */
@Composable
fun Golf3D(mask: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val failed = remember { mutableStateOf(isRobolectric()) }
    val panel = remember { mutableStateOf<Pair<String, String>?>(null) }
    val holder = remember { arrayOfNulls<GolfScene>(1) }
    LaunchedEffect(mask) { holder[0]?.setDoors(mask) }
    DisposableEffect(Unit) { onDispose { holder[0]?.stop(); holder[0] = null } }
    Box(modifier.background(CardSolid)) {
        if (!failed.value) AndroidView(
            factory = { c ->
                TextureView(c).also { sv ->
                    val r = runCatching {
                        GolfScene(c.applicationContext, sv, onPanel = { panel.value = it }).also {
                            it.setDoors(mask); holder[0] = it
                        }
                    }
                    r.exceptionOrNull()?.let { e ->
                        WakeLog.add(c, "Golf 3D : démarrage impossible (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                        failed.value = true
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
        )
        if (failed.value) Column(
            Modifier.matchParentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.golf_profil), "Ma Golf", Modifier.fillMaxWidth(0.8f), contentScale = ContentScale.Fit)
            Text("Vue 3D indisponible (détail dans le journal)", color = Muted, fontSize = 12.sp)
        }
        panel.value?.let { (title, text) ->
            Column(
                Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(14.dp)).background(CardSolid).padding(12.dp),
            ) {
                Text(title, color = AccentText, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(text, color = Ink, fontSize = 12.sp, lineHeight = 16.sp)
                Text(
                    "Fermer", color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Yellow)
                        .clickable { panel.value = null }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            "Modèle 3D : bimboit34 (Sketchfab) · CC BY 4.0", color = Muted.copy(alpha = 0.7f), fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

/** Réglages de rendu (modifiables par l'écran de test sur émulateur). */
object Golf3DOptions { var post = false; var msaa = true }

private fun isRobolectric() = android.os.Build.FINGERPRINT == "robolectric"

/** Scène Filament de la Golf : chargement, caméra, ouvrants, appui sur une pièce, boucle d'image. */
private class GolfScene(
    private val app: Context, private val sv: TextureView, private val onPanel: (Pair<String, String>?) -> Unit,
) : Choreographer.FrameCallback {
    private val meta: JSONObject
    private val manip: Manipulator
    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val materials: UbershaderProvider
    private val loader: AssetLoader
    private val resLoader: ResourceLoader
    private val asset: FilamentAsset
    private val sun: Int
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val display: DisplayHelper
    private var swapChain: SwapChain? = null
    private var srgb = false
    private val choreographer = Choreographer.getInstance()
    private var alive = true
    private var activeUntil = 0L
    private var lastFrame = 0L

    private class Door(val entity: Int, val base: FloatArray, val axisX: Boolean, val sign: Float, val max: Float) {
        var t = 0f; var target = 0f
    }
    private val doors = HashMap<String, Door>()
    private var shelf: Pair<Int, FloatArray>? = null
    private var bayEntities = IntArray(0)
    private var bayShown = true
    private var pendingMask = 0

    init {
        Utils.init()
        meta = JSONObject(app.assets.open("golf/golf.json").bufferedReader().readText())
        val c = meta.getJSONArray("center"); val h = meta.getJSONArray("home")
        val cx = c.getDouble(0).toFloat(); val cy = c.getDouble(1).toFloat(); val cz = c.getDouble(2).toFloat()
        // Vue de départ de 3/4 avant, plus rapprochée qu'avant (demande du 22/11)
        val k = 0.72f
        val hx = cx + (h.getDouble(0).toFloat() - cx) * k
        val hy = cy + (h.getDouble(1).toFloat() - cy) * k
        val hz = cz + (h.getDouble(2).toFloat() - cz) * k
        manip = Manipulator.Builder()
            .targetPosition(cx, cy, cz)
            .orbitHomePosition(hx, hy, hz)
            .upVector(0f, 1f, 0f)
            .orbitSpeed(0.0035f, 0.0035f)
            .zoomSpeed(0.05f)
            .viewport(maxOf(sv.width, 1), maxOf(sv.height, 1))
            .build(Manipulator.Mode.ORBIT)

        engine = Engine.create()
        srgb = SwapChain.isSRGBSwapChainSupported(engine)
        renderer = engine.createRenderer()
        scene = engine.createScene()
        camera = engine.createCamera(engine.entityManager.create()).apply { setExposure(16f, 1f / 125f, 100f) }
        view = engine.createView().also { it.scene = scene; it.camera = camera }
        // Fond transparent : la carte Koackpit (couleur du thème) se voit derrière la voiture
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = floatArrayOf(0f, 0f, 0f, 0f) }
        view.blendMode = View.BlendMode.TRANSLUCENT
        // Sans post-traitement : bien plus léger pour la puce de l'autoradio, et le seul rendu fiable constaté
        // sur émulateur. Couleurs justes grâce à une surface sRGB ; bords lissés par multi-échantillonnage.
        view.isPostProcessingEnabled = Golf3DOptions.post
        view.antiAliasing = if (Golf3DOptions.post) View.AntiAliasing.FXAA else View.AntiAliasing.NONE
        if (Golf3DOptions.msaa) view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }

        // Modèle
        materials = UbershaderProvider(engine)
        loader = AssetLoader(engine, materials, EntityManager.get())
        resLoader = ResourceLoader(engine, true)
        asset = loader.createAsset(direct(app.assets.open("golf/golf.glb").readBytes()))
            ?: throw IllegalStateException("modèle illisible")
        resLoader.asyncBeginLoad(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)

        // Lumière : environnement studio + soleil, sans ombres calculées (ombre au sol déjà dessinée dans le modèle).
        // Plus forte sans surface sRGB (sinon image trop sombre).
        val boost = if (srgb || Golf3DOptions.post) 1f else 2.2f
        scene.indirectLight = KTX1Loader.createIndirectLight(engine, direct(app.assets.open("golf/ibl.ktx").readBytes()))
            .also { it.intensity = 30_000f * boost }
        sun = EntityManager.get().create()
        val (r, g, bl) = Colors.cct(6_500f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(r, g, bl).intensity(90_000f * boost)
            .direction(-0.45f, -0.85f, -0.3f).castShadows(false).build(engine, sun)
        scene.addEntity(sun)

        display = DisplayHelper(app)
        ui.isOpaque = false
        ui.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                val flags = SwapChainFlags.CONFIG_TRANSPARENT or (if (srgb && !Golf3DOptions.post) SwapChainFlags.CONFIG_SRGB_COLORSPACE else 0L)
                swapChain = engine.createSwapChain(surface, flags)
                sv.display?.let { display.attach(renderer, it) }
                wake(1500)
            }
            override fun onDetachedFromSurface() {
                display.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait(); swapChain = null }
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                manip.setViewport(width, height)
                camera.setLensProjection(28.0, width.toDouble() / maxOf(height, 1), 1.0, 5000.0)
                wake(1500)
            }
        }
        ui.attachTo(sv)
        setupParts()
        setDoors(pendingMask)
        // Gestes : ModelViewer tourne / zoome ; un appui court ouvre la fiche de la pièce touchée
        val taps = android.view.GestureDetector(app, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { pick(e.x, e.y); return true }
        })
        sv.setOnTouchListener { _, e -> touch(e); taps.onTouchEvent(e); wake(); true }
        // Retour sur l'écran (surface recréée) : redessiner
        sv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> wake(1500) }
        wake(4000)
        choreographer.postFrameCallback(this)
        WakeLog.add(app, "Golf 3D : moteur 3D natif prêt (${meta.getJSONArray("infos").length()} pièces, sRGB ${if (srgb) "oui" else "non"})")
    }

    private fun setupParts() {
                val tm = engine.transformManager
        val d = meta.getJSONObject("doors")
        for (key in d.keys()) {
            val e = asset.getFirstEntityByName("open_$key")
            if (e == 0) continue
            val a = d.getJSONArray(key)
            val base = tm.getTransform(tm.getInstance(e), FloatArray(16))
            doors[key] = Door(e, base, a.getString(0) == "x", a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
        }
        asset.getFirstEntityByName("open_SHELF").takeIf { it != 0 }?.let { shelf = it to tm.getTransform(tm.getInstance(it), FloatArray(16)) }
        asset.getFirstEntityByName("bay").takeIf { it != 0 }?.let { bay ->
            val out = ArrayList<Int>()
            fun walk(e: Int) {
                out.add(e)
                val ti = tm.getInstance(e)
                if (ti == 0) return
                for (ch in tm.getChildren(ti, null)) walk(ch)
            }
            walk(bay); bayEntities = out.toIntArray()
        }
    }

    fun setDoors(mask: Int) {
        pendingMask = mask
        if (doors.isEmpty()) return
        val bits = mapOf("FL" to Doors.FL, "FR" to Doors.FR, "RL" to Doors.RL, "RR" to Doors.RR, "HOOD" to Doors.HOOD, "TAIL" to Doors.TRUNK)
        for ((k, d) in doors) d.target = if (mask and (bits[k] ?: 0) != 0) 1f else 0f
        wake()
    }

    private fun wake(ms: Long = 1500) { activeUntil = maxOf(activeUntil, System.nanoTime() + ms * 1_000_000) }

    private fun ease(t: Float) = if (t < .5f) 4 * t * t * t else 1 - Math.pow((-2.0 * t + 2), 3.0).toFloat() / 2

    /** base × rotation (axe X ou Z local), matrices 4×4 en colonnes. */
    private fun rotated(base: FloatArray, axisX: Boolean, deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val rot = if (axisX) floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
        else floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val o = FloatArray(16)
        for (col in 0..3) for (row in 0..3) {
            var v = 0f
            for (k in 0..3) v += base[k * 4 + row] * rot[col * 4 + k]
            o[col * 4 + row] = v
        }
        return o
    }

    private fun animate(dt: Float): Boolean {
        val tm = engine.transformManager
        var moving = false
        for ((k, d) in doors) {
            if (d.t == d.target) continue
            moving = true
            d.t = if (d.target > d.t) minOf(d.target, d.t + dt / 0.9f) else maxOf(d.target, d.t - dt / 0.7f)
            tm.setTransform(tm.getInstance(d.entity), rotated(d.base, d.axisX, d.sign * d.max * ease(d.t)))
            if (k == "TAIL") shelf?.let { (e, b) -> tm.setTransform(tm.getInstance(e), rotated(b, true, 26f * ease(d.t))) }
        }
        // Compartiment moteur : dessiné seulement capot ouvert (invisible sinon, et plus léger)
        val hood = doors["HOOD"]
        val show = hood == null || hood.t > 0f || hood.target > 0f
        if (show != bayShown) {
            bayShown = show
            val rm = engine.renderableManager
            for (e in bayEntities) { val ri = rm.getInstance(e); if (ri != 0) rm.setLayerMask(ri, 0xff, if (show) 0x01 else 0x00) }
        }
        return moving
    }

    // Gestes maison : un doigt = tourner autour de la voiture, deux doigts = zoom (borné). Pas de déplacement latéral.
    private var mode = 0 // 0 rien, 1 rotation, 2 zoom
    private var downX = 0f; private var downY = 0f; private var lastSep = 0f
    private val slop = android.view.ViewConfiguration.get(app).scaledTouchSlop

    private fun sep(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1); return sqrt(dx * dx + dy * dy)
    }

    private fun touch(e: MotionEvent) {
        val h = sv.height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { mode = 0; downX = e.x; downY = e.y; onPanel(null) }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == 1) manip.grabEnd()
                if (e.pointerCount == 2) { mode = 2; lastSep = sep(e) }
            }
            MotionEvent.ACTION_MOVE -> when {
                mode == 2 && e.pointerCount >= 2 -> {
                    val s = sep(e); zoom((lastSep - s) * 0.1f); lastSep = s
                }
                mode == 1 -> manip.grabUpdate(e.x.toInt(), h - e.y.toInt())
                mode == 0 && e.pointerCount == 1 && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop) -> {
                    mode = 1; manip.grabBegin(downX.toInt(), h - downY.toInt(), false)
                    manip.grabUpdate(e.x.toInt(), h - e.y.toInt())
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == 2) mode = 3   // fin du zoom : on ignore le doigt restant
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 1) manip.grabEnd(); mode = 0
                if (BuildConfig.DEBUG) { manip.getLookAt(eye, tgt, up); android.util.Log.i("Golf3D", "caméra ${eye.joinToString()} → ${tgt.joinToString()}") }
            }
        }
    }

    private val eye = FloatArray(3); private val tgt = FloatArray(3); private val up = FloatArray(3)
    private fun camDist(): Float {
        manip.getLookAt(eye, tgt, up)   // tgt = un point devant la caméra (pas le centre) : on mesure jusqu'au centre
        val c = meta.getJSONArray("center")
        return dist(floatArrayOf(eye[0] - c.getDouble(0).toFloat(), eye[1] - c.getDouble(1).toFloat(), eye[2] - c.getDouble(2).toFloat()))
    }

    /** Zoom vers le centre, borné : ni dans la voiture, ni trop loin. delta > 0 = recule. */
    private fun zoom(delta: Float) {
        if (delta == 0f) return
        val min = meta.optDouble("minDist", 100.0).toFloat() * 0.85f
        val max = meta.optDouble("maxDist", 600.0).toFloat() * 0.8f
        val d = camDist()
        if ((delta < 0 && d <= min) || (delta > 0 && d >= max)) return
        manip.scroll(sv.width / 2, sv.height / 2, delta)
    }

    private fun pick(x: Float, y: Float) {
        val h = view.viewport.height
        view.pick(x.toInt(), h - y.toInt(), android.os.Handler(android.os.Looper.getMainLooper())) { r ->
                        val tm = engine.transformManager
            var e = r.renderable
            var guard = 0
            while (e != 0 && guard++ < 30) {
                val name = asset.getName(e)
                if (name != null && name.startsWith("info_")) {
                    val i = name.removePrefix("info_").toIntOrNull()
                    val infos = meta.getJSONArray("infos")
                    if (i != null && i < infos.length()) {
                        val o = infos.getJSONObject(i); onPanel(o.getString("title") to o.getString("text"))
                    }
                    return@pick
                }
                val ti = tm.getInstance(e); if (ti == 0) break
                e = tm.getParent(ti)
            }
            onPanel(null)
        }
        wake()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!alive) return
        choreographer.postFrameCallback(this)
        val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = frameTimeNanos
        if (animate(dt)) wake()
        if (resLoader.asyncGetLoadProgress() < 1f) wake(500)       // textures encore en chargement
        if (frameTimeNanos > activeUntil) return   // rien ne bouge : pas d'image (puce graphique au repos)
        render(frameTimeNanos)
    }

    private val eyeD = DoubleArray(3); private val tgtD = DoubleArray(3); private val upD = DoubleArray(3)
    private fun render(t: Long) {
        val sc = swapChain ?: return
        if (!ui.isReadyToRender) return
        resLoader.asyncUpdateLoad()
        manip.getLookAt(eyeD, tgtD, upD)
        camera.lookAt(eyeD[0], eyeD[1], eyeD[2], tgtD[0], tgtD[1], tgtD[2], upD[0], upD[1], upD[2])
        if (renderer.beginFrame(sc, t)) { renderer.render(view); renderer.endFrame() }
    }

    /** Écran quitté : tout est libéré (mémoire graphique rendue). */
    fun stop() {
        if (!alive) return
        alive = false; choreographer.removeFrameCallback(this)
        runCatching {
            ui.detach()
            resLoader.asyncCancelLoad()
            loader.destroyAsset(asset); loader.destroy()
            materials.destroyMaterials(); materials.destroy(); resLoader.destroy()
            engine.destroyEntity(sun); EntityManager.get().destroy(sun)
            engine.destroyRenderer(renderer); engine.destroyView(view); engine.destroyScene(scene)
            engine.destroyCameraComponent(camera.entity); EntityManager.get().destroy(camera.entity)
            engine.destroy()
        }
    }

    private fun dist(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
}

private fun direct(b: ByteArray): ByteBuffer =
    ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.rewind() }
