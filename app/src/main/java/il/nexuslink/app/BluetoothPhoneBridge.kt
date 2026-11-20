package il.nexuslink.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.lang.reflect.InvocationTargetException

/**
 * Best-effort bridge to Android's OEM/system HFP Client (profile id 16)
 * and PBAP Client (profile id 17). These are System APIs, so availability
 * and call-control permissions depend on the head-unit firmware.
 */
class BluetoothPhoneBridge(
    private val context: Context,
    private val onChanged: () -> Unit
) : BluetoothProfile.ServiceListener {
    companion object {
        const val HFP_CLIENT = 16
        const val PBAP_CLIENT = 17
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var hfp: BluetoothProfile? = null
    @Volatile private var pbap: BluetoothProfile? = null
    @Volatile var selectedDevice: BluetoothDevice? = null
        private set
    @Volatile var lastError: String = ""

    val hfpAvailable: Boolean get() = hfp != null
    val pbapAvailable: Boolean get() = pbap != null

    fun start() {
        val a = adapter ?: run { lastError = "Bluetooth לא נתמך"; onChanged(); return }
        try {
            a.getProfileProxy(context, this, HFP_CLIENT)
        } catch (e: Exception) {
            lastError = "HFP Client אינו חשוף לאפליקציה: ${rootMessage(e)}"
        }
        try {
            a.getProfileProxy(context, this, PBAP_CLIENT)
        } catch (e: Exception) {
            if (lastError.isBlank()) lastError = "PBAP Client אינו חשוף לאפליקציה: ${rootMessage(e)}"
        }
        onChanged()
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        when (profile) {
            HFP_CLIENT -> hfp = proxy
            PBAP_CLIENT -> pbap = proxy
        }
        lastError = ""
        onChanged()
    }

    override fun onServiceDisconnected(profile: Int) {
        when (profile) {
            HFP_CLIENT -> hfp = null
            PBAP_CLIENT -> pbap = null
        }
        onChanged()
    }

    fun connectedHfpDevices(): List<BluetoothDevice> =
        try { hfp?.connectedDevices ?: emptyList() } catch (_: Exception) { emptyList() }

    fun connectedPbapDevices(): List<BluetoothDevice> =
        try { pbap?.connectedDevices ?: emptyList() } catch (_: Exception) { emptyList() }

    fun connect(device: BluetoothDevice): Boolean {
        selectedDevice = device
        return invokeBoolean(hfp, "connect", device)
    }

    fun connectPhoneBook(device: BluetoothDevice): Boolean {
        selectedDevice = device
        return invokeBoolean(pbap, "connect", device)
    }

    fun dial(number: String): Boolean {
        val d = selectedDevice ?: return false
        if (number.isBlank()) return false
        val result = invoke(hfp, "dial", d, number)
        return result != null && (result !is Boolean || result)
    }

    fun answer(): Boolean {
        val d = selectedDevice ?: return false
        return invokeBoolean(hfp, "acceptCall", d, 0)
    }

    fun reject(): Boolean {
        val d = selectedDevice ?: return false
        return invokeBoolean(hfp, "rejectCall", d)
    }

    fun hangUp(): Boolean {
        val d = selectedDevice ?: return false
        val target = hfp ?: return false
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == "terminateCall" && it.parameterTypes.size == 2
            } ?: return invokeBoolean(target, "rejectCall", d)
            val second = method.parameterTypes[1]
            val value: Any? = if (second == Int::class.javaPrimitiveType || second == Int::class.javaObjectType) 0 else null
            (method.invoke(target, d, value) as? Boolean) ?: false
        } catch (e: Exception) {
            lastError = rootMessage(e)
            false
        }
    }

    fun sendDtmf(digit: Char): Boolean {
        val d = selectedDevice ?: return false
        return invokeBoolean(hfp, "sendDTMF", d, digit.code.toByte())
    }

    fun connectAudio(): Boolean = invokeBoolean(hfp, "connectAudio")
    fun disconnectAudio(): Boolean = invokeBoolean(hfp, "disconnectAudio")

    fun currentCalls(): List<RemoteCall> {
        val d = selectedDevice ?: return emptyList()
        val raw = invoke(hfp, "getCurrentCalls", d) as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val number = readProperty(item, "getNumber")?.toString().orEmpty()
            val state = (readProperty(item, "getState") as? Number)?.toInt() ?: -1
            val id = (readProperty(item, "getId") as? Number)?.toInt() ?: -1
            val outgoing = readProperty(item, "isOutgoing") as? Boolean ?: false
            RemoteCall(number, state, id, outgoing)
        }
    }

    private fun invokeBoolean(target: BluetoothProfile?, method: String, vararg args: Any?): Boolean =
        invoke(target, method, *args) as? Boolean ?: false

    private fun invoke(target: BluetoothProfile?, name: String, vararg args: Any?): Any? {
        if (target == null) {
            lastError = "שירות Bluetooth של Android אינו זמין"
            return null
        }
        return try {
            val method = target.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.indices.all { i -> compatible(m.parameterTypes[i], args[i]) }
            } ?: run {
                lastError = "פקודת $name אינה נתמכת בקושחת הנגן"
                return null
            }
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            lastError = rootMessage(e.targetException ?: e)
            null
        } catch (e: Exception) {
            lastError = rootMessage(e)
            null
        }
    }

    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (type.isAssignableFrom(value.javaClass)) return true
        return type == Byte::class.javaPrimitiveType && value is Byte ||
            type == Int::class.javaPrimitiveType && value is Int ||
            type == Boolean::class.javaPrimitiveType && value is Boolean
    }

    private fun readProperty(target: Any, name: String): Any? =
        try { target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(target) }
        catch (_: Exception) { null }

    private fun rootMessage(e: Throwable): String =
        (e.cause ?: e).message ?: (e.cause ?: e).javaClass.simpleName

    fun close() {
        val a = adapter ?: return
        try { hfp?.let { a.closeProfileProxy(HFP_CLIENT, it) } } catch (_: Exception) {}
        try { pbap?.let { a.closeProfileProxy(PBAP_CLIENT, it) } } catch (_: Exception) {}
        hfp = null
        pbap = null
    }
}

data class RemoteCall(
    val number: String,
    val state: Int,
    val id: Int,
    val outgoing: Boolean
) {
    fun stateLabel(): String = when (state) {
        0 -> "בשיחה"
        1 -> "בהמתנה"
        2 -> "בחיוג"
        3 -> "מצלצל"
        4 -> "שיחה נכנסת"
        5 -> "שיחה ממתינה"
        6 -> "בהמתנה"
        7 -> "הסתיימה"
        else -> "מצב לא ידוע"
    }
}
