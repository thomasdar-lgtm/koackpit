package com.koala.koackpit

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Method

/**
 * Lien direct avec le service « car » de l'autoradio (android.car.server), celui qu'utilisent
 * les applis d'origine : source audio, musique Bluetooth, et plus tard les données du véhicule.
 */
object CarLink {
    private const val DESCRIPTOR = "android.car.server.ICarService"
    private const val TX_REQUEST = 3

    const val SRC_RADIO = 65536
    const val SRC_BT = 65537
    const val SRC_ANDROID = 65539

    // Fonctions Bluetooth (ordre de BtDefine.BtFun)
    private const val FUN_PLAY_PAUSE = 13
    private const val FUN_NEXT = 15
    private const val FUN_PREV = 16

    // Types de données (ordre de CarDataManager.DataType)
    private const val TYPE_INT = 2
    private const val TYPE_STRING_ARRAY = 15

    private var binder: IBinder? = null

    private fun hidden(cls: Class<*>, name: String, vararg types: Class<*>): Method {
        val gdm = Class::class.java.getMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass)
        return (gdm.invoke(cls, name, types) as Method).apply { isAccessible = true }
    }

    private fun service(): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        binder = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            hidden(sm, "getService", String::class.java).invoke(null, "car") as IBinder?
        }.getOrNull()
        return binder
    }

    fun available() = service() != null

    /** Appel brut : renvoie le résultat et le Bundle rempli par le service. */
    private fun request(a: Int, b: Int, args: Bundle?): Pair<Int, Bundle?>? {
        val s = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(a)
            data.writeInt(b)
            if (args != null) { data.writeInt(1); args.writeToParcel(data, 0) } else data.writeInt(0)
            s.transact(TX_REQUEST, data, reply, 0)
            reply.readException()
            val r = reply.readInt()
            val out = if (reply.dataAvail() > 0 && reply.readInt() != 0) Bundle().also { it.readFromParcel(reply) } else null
            r to out
        } catch (e: Exception) {
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun value(key: String, type: Int): Bundle? =
        request(1, 100, Bundle().apply { putString("key", key); putInt("type", type) })?.second

    fun currentSource(): Int? = request(1, 4, null)?.first

    fun sourceName(s: Int?) = when (s) {
        null -> "inconnue"
        0 -> "aucune"
        SRC_RADIO -> "Radio"
        SRC_BT -> "Bluetooth"
        SRC_ANDROID -> "Android"
        else -> "autre ($s)"
    }

    /** Demande à l'autoradio d'entrer dans une source (comme les applis d'origine). */
    fun enterSource(id: Int): Boolean =
        request(1, 1, Bundle().apply { putInt("app_id", id); putString("reason", "koackpit") }) != null

    /** État A2DP : 0 repos, 1 lecture, 2 pause, 3 arrêt. */
    fun a2dpState(): Int? = value("state.bt.a2dp_state", TYPE_INT)?.takeIf { it.containsKey("value") }?.getInt("value")

    /** Caméra de recul affichée (marche arrière) : clé gérée par le service de l'autoradio. */
    fun inBackCar(): Boolean? = value("state.app.in_backcar", TYPE_INT)?.takeIf { it.containsKey("value") }?.getInt("value")?.let { it == 1 }

    /** Titre et artiste reçus du téléphone (AVRCP). */
    fun avrcpInfo(): Array<String>? = value("state.bt.avrcp_info", TYPE_STRING_ARRAY)?.getStringArray("value")

    private fun btFun(f: Int) = request(4, 2, Bundle().apply { putInt("bt_fun", f); putInt("arg_int", 0) }) != null
    fun playPause() = btFun(FUN_PLAY_PAUSE)
    fun next() = btFun(FUN_NEXT)
    fun prev() = btFun(FUN_PREV)
}
