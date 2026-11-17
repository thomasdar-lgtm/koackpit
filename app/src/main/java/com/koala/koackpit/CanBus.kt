package com.koala.koackpit

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf

/**
 * Données de la voiture lues sur le bus CAN, comme le fait l'appli Car Info de l'autoradio.
 *
 * Le boîtier CAN de la Golf (type 8 : Volkswagen plateforme PQ, boîtier « RZC ») envoie ses trames au
 * service « car » de l'autoradio, qui les redistribue à toute appli abonnée (rappel de type 64).
 * Format d'une trame : 0x2E, type, longueur, données…, somme de contrôle.
 * Vitesse : type 0x16 (22), 2 octets poids faible d'abord, valeur ÷ 16 = km/h (code de Car Info).
 */
object CanBus {
    private const val SERVICE = "android.car.server.ICarService"
    private const val CALLBACK = "android.car.server.ICarCallback"
    private const val TX_REGISTER = 1
    private const val CBK_CANBUS = 64
    private const val TYPE_SPEED = 22
    private const val TYPE_KEY = 32     // boutons du volant : code, état (1 appuyé, 0 relâché) — OK = 01 (constaté)
    private const val TYPE_DOORS = 36   // 2e octet : ouvrants (confirmé dans la voiture : porte conducteur = bit 0, coffre = bit 4)

    /** Vitesse lue dans la voiture (km/h), et quand (pour savoir si elle est encore fraîche). */
    val speed = mutableStateOf<Int?>(null)
    @Volatile private var speedAt = 0L
    /** Ouvrants ouverts (voir [Doors]). */
    val doors = mutableStateOf(0)

    fun freshSpeed(): Int? = speed.value?.takeIf { SystemClock.elapsedRealtime() - speedAt < 3000 }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var registeredOn: IBinder? = null

    // Relevé des types de trames reçues pendant la première minute (pour les prochaines données : portes…)
    private val counts = java.util.TreeMap<Int, Int>()
    private val lastHex = HashMap<Int, String>()
    private val logged = HashMap<Int, Int>()
    private var countUntil = 0L
    private var firstSpeedLogged = false

    /** Le rappel appelé par le service « car » (même format que l'interface ICarCallback du fabricant). */
    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            runCatching {
                data.enforceInterface(CALLBACK)
                when (code) {
                    1 -> {   // onCarCallback(type, what, bundle)
                        val type = data.readInt(); val what = data.readInt()
                        val b = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        if (type == CBK_CANBUS && what == 0) b?.getByteArray("arg_byte_array")?.let { onFrame(it) }
                    }
                    2, 3 -> { reply?.writeNoException(); reply?.writeInt(0); reply?.writeInt(0) }
                }
            }
            return true
        }
    }

    private fun onFrame(f: ByteArray) {
        if (f.size < 4 || (f[0].toInt() and 255) != 0x2E) return
        val type = f[1].toInt() and 255
        val len = f[2].toInt() and 255
        if (3 + len > f.size) return
        val now = SystemClock.elapsedRealtime()
        if (now < countUntil) {
            synchronized(counts) { counts[type] = (counts[type] ?: 0) + 1 }
            // Relevé brut : chaque nouveau contenu d'un type (8 au plus par type) est noté, pour décoder
            // les trames que Car Info n'utilise pas et associer chaque action (porte, phares…) à une trame.
            val hex = f.copyOfRange(3, 3 + len).joinToString(" ") { "%02X".format(it) }
            val changed = synchronized(counts) {
                if (lastHex[type] != hex && (logged[type] ?: 0) < 8) { lastHex[type] = hex; logged[type] = (logged[type] ?: 0) + 1; true } else false
            }
            if (changed) app?.let { WakeLog.add(it, "CAN type $type : $hex") }
        }
        if (now < captureUntil) capture(type, f.copyOfRange(3, 3 + len))
        // Boutons du volant : action désactivée. Le code 01 pris pour « OK » est en réalité un bouton de gauche
        // (relevé du 17/11 : 01, 06, 02, 05 pour les 4 boutons de gauche ; OK n'envoie rien sur le CAN).
        // if (type == TYPE_KEY && len >= 2) onKey(f[3].toInt() and 255, f[4].toInt() and 255)
        if (type == TYPE_DOORS && len >= 2) {
            val m = f[4].toInt() and 0x3F
            main.post { if (doors.value != m) doors.value = m }
        }
        if (type == TYPE_SPEED && len >= 2) {
            val raw = ((f[4].toInt() and 255) shl 8) or (f[3].toInt() and 255)
            val kmh = raw / 16
            if (kmh in 0..300) {
                speedAt = now
                main.post { speed.value = kmh }
                if (!firstSpeedLogged) {
                    firstSpeedLogged = true
                    app?.let { WakeLog.add(it, "Vitesse voiture (CAN) reçue : $kmh km/h") }
                }
            }
        }
    }

    // ---- Relevé à la demande (bouton de l'outil de diagnostic), enregistré dans releve-can.txt ----
    @Volatile private var captureUntil = 0L
    private val capLast = HashMap<String, String>()
    private val capCount = HashMap<String, Int>()
    private val capCounts = java.util.TreeMap<Int, Int>()

    fun captureFile(ctx: Context) = java.io.File(ctx.filesDir, "releve-can.txt")
    fun capturing() = SystemClock.elapsedRealtime() < captureUntil

    /** Relevé de [minutes] min : chaque nouveau contenu de chaque trame (40 au plus par trame) avec l'heure. */
    fun startCapture(ctx: Context, minutes: Int) {
        app = ctx.applicationContext
        synchronized(capLast) { capLast.clear(); capCount.clear(); capCounts.clear(); capWritten.clear(); capLines = 0 }
        startGps(ctx.applicationContext)
        captureFile(ctx).writeText("Relevé CAN démarré ${stamp()} pour $minutes min (Koackpit ${BuildConfig.VERSION_NAME})\n")
        captureUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
        WakeLog.add(ctx, "Relevé voiture démarré ($minutes min)")
        main.postDelayed({
            val s = synchronized(capLast) { capCounts.entries.joinToString(", ") { "type ${it.key}×${it.value}" } }
            app?.let {
                stopGps(it)
                runCatching { captureFile(it).appendText("Fin ${stamp()} — trames : $s\n") }
                WakeLog.add(it, "Relevé voiture terminé : " + s.ifEmpty { "aucune trame" })
            }
        }, minutes * 60_000L)
    }

    private fun stamp() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())

    private val capWritten = HashMap<String, Long>()
    private var capLines = 0
    @Volatile private var gpsKmh: Float? = null
    private var gpsListener: android.location.LocationListener? = null

    /**
     * Une ligne par trame et par seconde au plus (sous-type = 1er octet pour 65 et 125), avec la vitesse GPS
     * du moment : permet de retrouver quelle trame porte la vitesse, le carburant… en les comparant.
     */
    private fun capture(type: Int, data: ByteArray) {
        val key = if ((type == 65 || type == 125) && data.isNotEmpty()) "$type/${"%02X".format(data[0])}" else "$type"
        val hex = data.joinToString(" ") { "%02X".format(it) }
        val now = SystemClock.elapsedRealtime()
        val write = synchronized(capLast) {
            capCounts[type] = (capCounts[type] ?: 0) + 1
            if (capLast[key] != hex && now - (capWritten[key] ?: 0L) >= 1000 && capLines < 6000) {
                capLast[key] = hex; capWritten[key] = now; capLines++; true
            } else false
        }
        if (write) app?.let {
            val g = gpsKmh?.let { v -> " | GPS ${"%.1f".format(v)}" } ?: ""
            runCatching { captureFile(it).appendText("${stamp()} $key : $hex$g\n") }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val l = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) { gpsKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        runCatching { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
        gpsListener = l
    }

    private fun stopGps(ctx: Context) {
        gpsListener?.let { l -> runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(l) } }
        gpsListener = null; gpsKmh = null
    }

    private var keyDown = false
    private var lastKeyAt = 0L

    /** Bouton OK du volant (écran « audio » du tableau de bord) → lecture / pause de la musique Bluetooth. */
    private fun onKey(code: Int, state: Int) {
        val now = SystemClock.elapsedRealtime()
        if (code == 0x01 && state != 0) {
            if (keyDown || now - lastKeyAt < 400) return
            keyDown = true; lastKeyAt = now
            Thread {
                if (CarLink.currentSource() == CarLink.SRC_BT) {
                    CarLink.playPause()
                    app?.let { WakeLog.add(it, "Volant : OK → lecture/pause") }
                }
            }.start()
        } else if (state == 0) keyDown = false
    }

    private fun carService(): IBinder? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "car") as IBinder?
    }.getOrNull()

    /** Abonnement aux trames CAN (au démarrage du service Koackpit ; renouvelé si le service « car » redémarre). */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        val car = carService() ?: run { WakeLog.add(ctx, "CAN : service car introuvable"); return }
        if (registeredOn === car && car.isBinderAlive) return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        val ok = try {
            val args = Bundle().apply {
                putString("package_name", ctx.packageName)
                putString("name", "Koackpit")
                putInt("cbk_set", CBK_CANBUS)
                putStringArray("key_filter", arrayOf("koackpit.aucune_donnee"))   // pas d'autres notifications
            }
            data.writeInterfaceToken(SERVICE)
            data.writeInt(1); args.writeToParcel(data, 0)
            data.writeStrongBinder(callback)
            car.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            WakeLog.add(ctx, "CAN : abonnement refusé (${e.javaClass.simpleName}: ${e.message})"); false
        } finally { data.recycle(); reply.recycle() }
        if (!ok) return
        registeredOn = car
        runCatching { car.linkToDeath({ registeredOn = null; main.postDelayed({ app?.let { start(it) } }, 3000) }, 0) }
        // Relevé de deux minutes
        synchronized(counts) { counts.clear(); lastHex.clear(); logged.clear() }
        countUntil = SystemClock.elapsedRealtime() + 120_000
        main.postDelayed({
            val s = synchronized(counts) { counts.entries.joinToString(", ") { "type ${it.key}×${it.value}" } }
            app?.let { WakeLog.add(it, "CAN, trames en 2 min : " + s.ifEmpty { "aucune (contact coupé ou boîtier muet ?)" }) }
        }, 120_000)
        WakeLog.add(ctx, "CAN : abonné")
    }
}
