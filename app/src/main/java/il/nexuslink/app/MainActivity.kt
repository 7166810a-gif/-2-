package il.nexuslink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private lateinit var status: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var hfpStatus: TextView
    private lateinit var pbapStatus: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var details: TextView
    private var selected: BluetoothDevice? = null

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))
            if (device?.address == selected?.address) showRemoteServices(device)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(uuidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(uuidReceiver, filter)
        buildUi()
        refresh()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 71)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 25, 22, 26)
            setBackgroundColor(0xFF07111F.toInt())
        }
        fun label(text: String, size: Float, color: Int = 0xFFEAF6FF.toInt()) =
            TextView(this).apply { this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(8, 9, 8, 9) }
        fun section(text: String) { root.addView(label(text, 17f, 0xFF45D9FF.toInt())) }
        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = text; setTextColor(0xFF07111F.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF45D9FF.toInt())
                setOnClickListener { action() }
            })
        }
        root.addView(label("NEXUSLINK", 30f, 0xFF45D9FF.toInt()))
        root.addView(label("OFFLINE • SIMPLE PHONE • ANDROID 13", 11f, 0xFF91AFC5.toInt()))
        status = label("בודק Bluetooth…", 15f, 0xFF75E4A5.toInt())
        root.addView(status)
        section("מבנה המערכת")
        root.addView(label("האפליקציה מותקנת בנגן בלבד. הטלפון הפשוט אינו מתקין אפליקציה ואינו זקוק לאינטרנט או לנתונים סלולריים. ה-SIM והשיחות נשארים בטלפון.", 14f))
        button("פתח הגדרות Bluetooth") {
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            catch (e: Exception) { status.text = "לא ניתן לפתוח הגדרות: ${e.message}" }
        }
        section("בחירת הטלפון")
        selectedLabel = label("לא נבחר טלפון מצומד", 14f, 0xFFB9CAD8.toInt()); root.addView(selectedLabel)
        remoteStatus = label("HFP Audio Gateway: טרם נבדק\nPBAP Phone Book Server: טרם נבדק", 14f); root.addView(remoteStatus)
        button("בחר טלפון מצומד ובדוק שירותים") { choosePhone() }
        section("בדיקת יכולות הנגן")
        hfpStatus = label("HFP Client (דיבורית): טרם נבדק", 14f); root.addView(hfpStatus)
        pbapStatus = label("PBAP Client (אנשי קשר): טרם נבדק", 14f); root.addView(pbapStatus)
        button("בדוק שוב שירותי HFP / PBAP") { probeClientProfiles() }
        section("אבחון")
        details = label("נדרש HFP Client בקושחת הנגן כדי לנהל שיחות דרך הטלפון. נדרש PBAP Client כדי לייבא אנשי קשר ויומן שיחות. חיבור Bluetooth למוזיקה בלבד אינו מספיק.", 13f, 0xFFB9CAD8.toInt())
        root.addView(details)
        root.addView(label("אין הרשאת INTERNET באפליקציה • NexusLink 0.2", 11f, 0xFF69859A.toInt()))
        scroll.addView(root); setContentView(scroll)
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val a = adapter
        if (a == null) { status.text = "Bluetooth אינו נתמך במכשיר"; return }
        if (!hasPermission()) { status.text = "יש לאשר הרשאת Bluetooth"; return }
        status.text = if (a.isEnabled) "Bluetooth פעיל • מצב אופליין" else "Bluetooth כבוי"
        probeClientProfiles()
    }

    private fun choosePhone() {
        if (!hasPermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 71)
            return
        }
        val devices = try { adapter?.bondedDevices?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        if (devices.isEmpty()) { status.text = "אין טלפונים מצומדים. בצע צימוד בהגדרות Android."; return }
        val names = devices.map { try { "${it.name ?: "Bluetooth"} • ${it.address}" } catch (_: Exception) { it.address } }
        AlertDialog.Builder(this).setTitle("בחר טלפון פשוט").setItems(names.toTypedArray()) { _, index ->
            selected = devices[index]
            selectedLabel.text = names[index]
            remoteStatus.text = "מבקש מידע שירותים מהטלפון…"
            try {
                showRemoteServices(devices[index])
                devices[index].fetchUuidsWithSdp()
            } catch (e: Exception) { remoteStatus.text = "בדיקת שירותי Bluetooth נכשלה: ${e.message}" }
        }.show()
    }

    private fun showRemoteServices(device: BluetoothDevice) {
        if (!hasPermission()) return
        val uuids = try { device.uuids?.map { it.uuid.toString().lowercase() }.orEmpty() } catch (_: Exception) { emptyList() }
        val hfpAg = uuids.any { it.startsWith("0000111f-") }
        val pbapPse = uuids.any { it.startsWith("0000112f-") }
        runOnUiThread {
            remoteStatus.text = "HFP Audio Gateway בטלפון: ${if (hfpAg) "נמצא" else "לא נמצא / לא פורסם"}\nPBAP Phone Book Server בטלפון: ${if (pbapPse) "נמצא" else "לא נמצא / לא פורסם"}"
            details.text = if (uuids.isEmpty())
                "הטלפון לא החזיר UUID ב-SDP. ייתכן שהמידע אינו מפורסם או שיש לנסות שוב לאחר צימוד."
            else
                "נמצאו ${uuids.size} שירותי Bluetooth בטלפון. זה אינו מוכיח שהנגן עצמו תומך בתפקיד HFP Client."
        }
    }

    // AOSP profile IDs: HEADSET_CLIENT=16, PBAP_CLIENT=17. These are not public SDK APIs.
    // This is a best-effort availability probe only; call control requires OEM/system privileges.
    private fun probeClientProfiles() {
        if (!hasPermission()) return
        val a = adapter ?: return
        if (!a.isEnabled) {
            hfpStatus.text = "HFP Client: Bluetooth כבוי"
            pbapStatus.text = "PBAP Client: Bluetooth כבוי"
            return
        }
        hfpStatus.text = "HFP Client: בודק שירות מערכת…"
        pbapStatus.text = "PBAP Client: בודק שירות מערכת…"
        try {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val active = try { proxy.connectedDevices.isNotEmpty() } catch (_: Exception) { false }
                    if (profile == 16) runOnUiThread {
                        hfpStatus.text = if (active) "HFP Client: שירות זמין ויש חיבור" else "HFP Client: שירות זמין, טרם התחבר"
                    }
                    try { a.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == 16) runOnUiThread { hfpStatus.text = "HFP Client: שירות לא זמין" }
                }
            }, 16)
        } catch (_: SecurityException) {
            hfpStatus.text = "HFP Client: נדרשת הרשאת יצרן/מערכת"
        } catch (e: Exception) {
            hfpStatus.text = "HFP Client: לא זמין (${e.message})"
        }
        try {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val active = try { proxy.connectedDevices.isNotEmpty() } catch (_: Exception) { false }
                    runOnUiThread {
                        pbapStatus.text = if (active) "PBAP Client: שירות זמין ויש חיבור" else "PBAP Client: שירות זמין, טרם התחבר"
                    }
                    try { a.closeProfileProxy(profile, proxy) } catch (_: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {
                    runOnUiThread { pbapStatus.text = "PBAP Client: שירות לא זמין" }
                }
            }, 17)
        } catch (_: SecurityException) {
            pbapStatus.text = "PBAP Client: נדרשת הרשאת יצרן/מערכת"
        } catch (e: Exception) {
            pbapStatus.text = "PBAP Client: לא זמין (${e.message})"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) refresh()
    }

    override fun onDestroy() {
        try { unregisterReceiver(uuidReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
