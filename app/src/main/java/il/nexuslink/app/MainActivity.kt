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
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var bridge: BluetoothPhoneBridge? = null
    private lateinit var status: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var hfpLabel: TextView
    private lateinit var pbapLabel: TextView
    private lateinit var callState: TextView
    private lateinit var number: EditText
    private lateinit var log: TextView
    private lateinit var root: LinearLayout
    private var selected: BluetoothDevice? = null
    private var page = "home"
    private var largeText = false

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val d = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))
            d?.let { if (it.address == selected?.address) showPhoneServices(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(uuidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(uuidReceiver, filter)
        bridge = BluetoothPhoneBridge(this) { runOnUiThread { updateProfileStatus() } }
        buildShell()
        requestBluetoothPermission()
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 15f, color: Int = 0xFFEAF6FF.toInt()) =
        TextView(this).apply { text=s; textSize=if (largeText) size+3 else size; setTextColor(color); gravity=Gravity.CENTER; setPadding(dp(8),dp(9),dp(8),dp(9)) }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text=s; textSize=14f; isAllCaps=false
        setTextColor(0xFF06121F.toInt())
        backgroundTintList=android.content.res.ColorStateList.valueOf(0xFF42D9F5.toInt())
        setOnClickListener { action() }
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10))
        setBackgroundColor(0xFF12263A.toInt())
        val p=LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,dp(6)); layoutParams=p
    }
    private fun section(parent: LinearLayout, title: String) { parent.addView(text(title,17f,0xFF48DDF5.toInt())) }
    private fun buildShell() {
        val outer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xFF07111F.toInt()) }
        val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(8)); setBackgroundColor(0xFF0B1C2D.toInt()) }
        header.addView(text("NEXUSLINK",25f,0xFF48DDF5.toInt()))
        header.addView(text("SMART PHONE LINK  •  OFFLINE",10f,0xFF8BAFC5.toInt()))
        status=text("מאתחל Bluetooth…",13f,0xFF77E5AA.toInt()); header.addView(status)
        outer.addView(header)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        listOf("ראשי","חייגן","שיחה","הגדרות").forEachIndexed { i, label ->
            nav.addView(Button(this).apply {
                text=label; textSize=11f; isAllCaps=false; setTextColor(0xFFEAF6FF.toInt())
                backgroundTintList=android.content.res.ColorStateList.valueOf(0xFF19354D.toInt())
                setOnClickListener { showPage(listOf("home","dial","call","settings")[i]) }
            }, LinearLayout.LayoutParams(0,-2,1f))
        }
        outer.addView(nav)
        val scroll=ScrollView(this)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(10),dp(16),dp(22)) }
        scroll.addView(root); outer.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(outer)
        showPage("home")
    }

    private fun showPage(which: String) {
        page=which; root.removeAllViews()
        when(which) {
            "home" -> homePage()
            "dial" -> dialPage()
            "call" -> callPage()
            else -> settingsPage()
        }
    }

    private fun homePage() {
        root.addView(text("מרכז שליטה",23f))
        root.addView(text("חיבור מקומי בין הנגן לטלפון פשוט. אין צורך באפליקציה בטלפון או בחיבור אינטרנט.",13f,0xFFB6CADA.toInt()))
        val c=card(); root.addView(c)
        c.addView(text("הטלפון המחובר",17f,0xFF49DDF5.toInt()))
        selectedLabel=text("לא נבחר טלפון",15f); c.addView(selectedLabel)
        c.addView(button("בחר טלפון מצומד") { choosePhone() })
        c.addView(button("פתח הגדרות Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        val p=card(); root.addView(p)
        p.addView(text("יכולות מערכת",17f,0xFF49DDF5.toInt()))
        hfpLabel=text("HFP Client: בבדיקה"); p.addView(hfpLabel)
        pbapLabel=text("PBAP Client: בבדיקה"); p.addView(pbapLabel)
        p.addView(button("בדוק שירותים מחדש") { bridge?.start(); updateProfileStatus() })
        val q=card(); root.addView(q)
        q.addView(text("פעולות מהירות",17f,0xFF49DDF5.toInt()))
        q.addView(button("פתח חייגן") { showPage("dial") })
        q.addView(button("מסך שיחה") { showPage("call") })
        q.addView(button("אנשי קשר ויומן") { showPage("settings"); log.text="אנשי קשר ויומן מרחוק דורשים PBAP Client פעיל בקושחת הנגן. מצב השירות מוצג למעלה." })
    }

    private fun dialPage() {
        root.addView(text("חייגן",24f))
        root.addView(text("החיוג יישלח לטלפון המצומד רק אם HFP Client זמין ומורשה.",13f,0xFFB6CADA.toInt()))
        number=EditText(this).apply { hint="הזן מספר טלפון"; textSize=25f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF7892A8.toInt()); gravity=Gravity.CENTER; inputType=3; setPadding(dp(12),dp(16),dp(12),dp(16)); setBackgroundColor(0xFF142A40.toInt()) }
        root.addView(number)
        val grid= listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("*","0","#"))
        grid.forEach { row ->
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            row.forEach { digit -> line.addView(button(digit) { number.append(digit) },LinearLayout.LayoutParams(0,dp(58),1f)) }
            root.addView(line)
        }
        root.addView(button("⌫ מחק") { val s=number.text.toString(); if(s.isNotEmpty()) number.setText(s.dropLast(1)) })
        root.addView(button("התקשר דרך Bluetooth") {
            val n=number.text.toString().trim()
            if(n.isBlank()) toast("הזן מספר")
            else if(bridge?.dial(n)==true) toast("פקודת החיוג נשלחה")
            else toast("החיוג אינו זמין: ${bridge?.lastError ?: "HFP Client לא מחובר"}")
        })
        root.addView(button("מסך שיחה") { showPage("call") })
    }

    private fun callPage() {
        root.addView(text("מסך שיחה",24f))
        callState=text("אין שיחה פעילה",18f,0xFF70E3A5.toInt()); root.addView(callState)
        root.addView(text("מצב שיחה נקרא משירות HFP Client של הנגן, אם הוא זמין.",13f,0xFFB6CADA.toInt()))
        root.addView(button("ענה לשיחה") { actionResult("מענה", bridge?.answer()==true) })
        root.addView(button("דחה שיחה") { actionResult("דחייה", bridge?.reject()==true) })
        root.addView(button("נתק שיחה") { actionResult("ניתוק", bridge?.hangUp()==true) })
        root.addView(button("חבר שמע Bluetooth") { actionResult("חיבור שמע", bridge?.connectAudio()==true) })
        root.addView(button("נתק שמע Bluetooth") { actionResult("ניתוק שמע", bridge?.disconnectAudio()==true) })
        section(root,"מקשי DTMF")
        val keys=listOf("1","2","3","4","5","6","7","8","9","*","0","#")
        keys.chunked(3).forEach { row ->
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            row.forEach { k -> line.addView(button(k) { actionResult("DTMF $k",bridge?.sendDtmf(k[0])==true) },LinearLayout.LayoutParams(0,dp(52),1f)) }
            root.addView(line)
        }
        root.addView(button("רענן מצב שיחה") { refreshCallState() })
    }

    private fun settingsPage() {
        root.addView(text("הגדרות",24f))
        root.addView(text("התאמה לנגן Camera Pro 3 • Android 13",14f,0xFFB6CADA.toInt()))
        root.addView(button(if(largeText) "בטל טקסט גדול" else "הפעל טקסט גדול") { largeText=!largeText; showPage("settings") })
        root.addView(button("בדיקת Bluetooth") { refresh(); toast("הבדיקה עודכנה") })
        root.addView(button("ניהול צימודים") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        root.addView(button("מידע והרשאות אפליקציה") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))) })
        log=text("האפליקציה אינה דורשת הרשאת INTERNET. הטלפון הפשוט אינו מתקין אפליקציה.\n\nHFP Client ו-PBAP Client הם שירותי מערכת/יצרן; אם אינם זמינים בקושחה, אפליקציה רגילה אינה יכולה להוסיף אותם בעצמה.\n\nהקלטת שיחה אינה מופעלת בגרסה זו: התמיכה תלויה בקושחה, בחוק ובמדיניות מערכת.",13f,0xFFB6CADA.toInt())
        root.addView(log)
        root.addView(button("רישום תוצאות בדיקה") { log.text="HFP: ${bridge?.hfpAvailable}\nPBAP: ${bridge?.pbapAvailable}\nשגיאה אחרונה: ${bridge?.lastError ?: "אין"}" })
    }

    private fun choosePhone() {
        if(!hasPermission()) { requestBluetoothPermission(); return }
        val devices=try { adapter?.bondedDevices?.toList().orEmpty() } catch(_:Exception){ emptyList() }
        if(devices.isEmpty()) { toast("אין מכשירים מצומדים. בצע צימוד בהגדרות Bluetooth."); return }
        val labels=devices.map { try { "${it.name ?: "Bluetooth"} • ${it.address}" } catch(_:Exception){it.address} }
        AlertDialog.Builder(this).setTitle("בחר טלפון פשוט").setItems(labels.toTypedArray()) { _, i ->
            selected=devices[i]; selectedLabel.text=labels[i]
            try {
                devices[i].fetchUuidsWithSdp()
                val hfp=bridge?.connect(devices[i])==true
                val pbap=bridge?.connectPhoneBook(devices[i])==true
                toast("נשלחו בקשות חיבור. HFP=$hfp, PBAP=$pbap")
                updateProfileStatus()
            } catch(e:Exception) { toast("שגיאת Bluetooth: ${e.message}") }
        }.show()
    }

    private fun showPhoneServices(d: BluetoothDevice) {
        val uuids=try { d.uuids?.map { it.uuid.toString().lowercase() }.orEmpty() } catch(_:Exception){emptyList()}
        val hfp=uuids.any { it.startsWith("0000111f-") }
        val pbap=uuids.any { it.startsWith("0000112f-") }
        toast("בטלפון: HFP AG=${if(hfp) "כן" else "לא פורסם"}, PBAP PSE=${if(pbap) "כן" else "לא פורסם"}")
    }

    private fun updateProfileStatus() {
        hfpLabel.text="HFP Client בנגן: ${if(bridge?.hfpAvailable==true) "שירות נגיש" else "לא נגיש / חסום בקושחה"}"
        pbapLabel.text="PBAP Client בנגן: ${if(bridge?.pbapAvailable==true) "שירות נגיש" else "לא נגיש / חסום בקושחה"}"
        status.text=if(adapter?.isEnabled==true) "Bluetooth פעיל • עבודה מקומית בלבד" else "Bluetooth כבוי"
    }

    private fun refresh() {
        if(adapter==null) { status.text="Bluetooth אינו נתמך"; return }
        if(!hasPermission()) { status.text="נדרשת הרשאת Bluetooth"; return }
        status.text=if(adapter?.isEnabled==true) "Bluetooth פעיל • ללא אינטרנט" else "Bluetooth כבוי"
        bridge?.start()
    }

    private fun refreshCallState() {
        val calls=bridge?.currentCalls().orEmpty()
        callState.text=if(calls.isEmpty()) "לא התקבל מידע על שיחה פעילה" else calls.joinToString("\n") { "${it.number} • ${it.stateLabel()}" }
    }
    private fun actionResult(label:String, ok:Boolean) { toast(if(ok) "$label: הפקודה התקבלה" else "$label לא זמין: ${bridge?.lastError ?: "בדוק תמיכת HFP"}") }
    private fun toast(s:String) = Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun hasPermission() = Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun requestBluetoothPermission() {
        if(Build.VERSION.SDK_INT>=31 && !hasPermission()) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),71)
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray) { super.onRequestPermissionsResult(r,p,g); if(r==71) refresh() }
    override fun onDestroy() { try{unregisterReceiver(uuidReceiver)}catch(_:Exception){}; bridge?.close(); super.onDestroy() }
}
