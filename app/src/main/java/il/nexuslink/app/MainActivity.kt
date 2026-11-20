package il.nexuslink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val uuid = UUID.fromString("b3b8a7a0-2f31-4e8b-9d6a-1b7e3c4d5a60")
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var number: EditText
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private var role = "נגן"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        buildUi()
    }

    private fun requestNeededPermissions() {
        val p = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_CONNECT; p += Manifest.permission.BLUETOOTH_SCAN }
        val missing = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 9)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(22,30,22,22); setBackgroundColor(0xFF07111F.toInt()) }
        fun label(s:String,size:Float,color:Int=0xFFEAF6FF.toInt()) = TextView(this).apply { text=s; textSize=size; setTextColor(color); gravity=Gravity.CENTER; setPadding(4,10,4,10) }
        root.addView(label("NEXUSLINK",30f,0xFF45D9FF.toInt()))
        root.addView(label("BLUETOOTH • OFFLINE COCKPIT",12f,0xFF91AFC5.toInt()))
        status=label("מוכן לחיבור מקומי",15f,0xFF73F5C5.toInt()); root.addView(status)
        val roles=Spinner(this)
        roles.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("נגן","טלפון"))
        roles.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) { role=if(position==0) "נגן" else "טלפון" }
        }
        root.addView(roles)
        root.addView(actionButton("הפעל שרת / המתן לחיבור Bluetooth") { startServer() })
        root.addView(actionButton("בחר מכשיר מצומד והתחבר") { choosePairedDevice() })
        root.addView(actionButton("סנכרן שיחות אחרונות") { showCalls() })
        root.addView(actionButton("סנכרן אנשי קשר") { showContacts() })
        number=EditText(this).apply { hint="הזן מספר לחיוג"; setHintTextColor(0xFF8198AA.toInt()); setTextColor(-1); textSize=20f; inputType=3 }
        root.addView(number)
        root.addView(actionButton("חייג מהמכשיר המחובר") { dial(number.text.toString()) })
        root.addView(actionButton("נתק חיבור Bluetooth") { closeConnection() })
        log=label("יומן מערכת\nאין נתונים להצגה",13f,0xFFB9CAD8.toInt())
        root.addView(ScrollView(this).apply { addView(log) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun actionButton(text:String,action:()->Unit)=Button(this).apply {
        this.text=text; setTextColor(0xFF07111F.toInt()); backgroundTintList=android.content.res.ColorStateList.valueOf(0xFF45D9FF.toInt()); setOnClickListener { action() }
    }
    private fun setStatus(s:String) { runOnUiThread { status.text=s } }
    private fun send(message:String) { thread { try { socket?.outputStream?.write((message+"\n").toByteArray()) } catch(_:Exception) { setStatus("אין חיבור פעיל") } } }

    private fun startServer() {
        val a=adapter ?: return setStatus("Bluetooth לא נתמך")
        if(!hasBtPermission()) return setStatus("יש לאשר הרשאת Bluetooth")
        if(!a.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        thread {
            try { server=a.listenUsingRfcommWithServiceRecord("NexusLink",uuid); setStatus("ממתין למכשיר • $role"); socket=server?.accept(); setStatus("מחובר: ${socket?.remoteDevice?.name ?: "מכשיר"}"); listen() }
            catch(e:Exception) { setStatus("שגיאת שרת: ${e.message}") }
        }
    }
    private fun choosePairedDevice() {
        if(!hasBtPermission()) return setStatus("יש לאשר הרשאת Bluetooth")
        val devices=adapter?.bondedDevices?.toList() ?: emptyList()
        if(devices.isEmpty()) return setStatus("אין מכשירים מצומדים. בצע צימוד בהגדרות Android")
        AlertDialog.Builder(this).setTitle("בחר מכשיר מצומד").setItems(devices.map { "${it.name ?: "Bluetooth"} • ${it.address}" }.toTypedArray()) { _,which -> connect(devices[which]) }.show()
    }
    private fun connect(device:BluetoothDevice) {
        thread { try { adapter?.cancelDiscovery(); socket=device.createRfcommSocketToServiceRecord(uuid); socket?.connect(); setStatus("מחובר אל ${device.name ?: device.address}"); listen() }
            catch(e:Exception) { setStatus("החיבור נכשל: ${e.message}") } }
    }
    private fun listen() {
        thread { try { val reader=BufferedReader(InputStreamReader(socket?.inputStream)); while(true) { val line=reader.readLine() ?: break; runOnUiThread { log.text="התקבל: $line\n"+log.text }; if(line.startsWith("DIAL:")) runOnUiThread { dial(line.removePrefix("DIAL:")) } } }
            catch(_:Exception) { setStatus("החיבור הסתיים") } }
    }
    private fun showCalls() {
        if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED) return setStatus("נדרשת הרשאת יומן שיחות")
        val rows=mutableListOf<String>()
        try { contentResolver.query(CallLog.Calls.CONTENT_URI,arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.TYPE,CallLog.Calls.DATE),null,null,CallLog.Calls.DATE+" DESC")?.use { c -> while(c.moveToNext()&&rows.size<30) rows += "${c.getString(0)} • ${c.getLong(2)} • ${c.getInt(1)}" } }
        catch(e:Exception) { rows += "לא ניתן לקרוא יומן: ${e.message}" }
        log.text="שיחות אחרונות\n"+rows.joinToString("\n"); send("CALLS:"+rows.joinToString(";"))
    }
    private fun showContacts() {
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) return setStatus("נדרשת הרשאת אנשי קשר")
        val rows=mutableListOf<String>()
        try { contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")?.use { c -> while(c.moveToNext()&&rows.size<100) rows += "${c.getString(0)}: ${c.getString(1)}" } }
        catch(_:Exception) { rows += "לא ניתן לקרוא אנשי קשר" }
        log.text="אנשי קשר (עד 100)\n"+rows.joinToString("\n"); send("CONTACTS:"+rows.joinToString(";"))
    }
    private fun dial(value:String) {
        if(value.isBlank()) return setStatus("הזן מספר לחיוג")
        if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED) return setStatus("נדרשת הרשאת שיחות")
        try { startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:"+Uri.encode(value)))) } catch(e:Exception) { setStatus("החיוג נכשל: ${e.message}") }
    }
    private fun closeConnection() { try { socket?.close(); server?.close() } catch(_:Exception) {}; socket=null; setStatus("נותק") }
    private fun hasBtPermission()=Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    override fun onDestroy() { closeConnection(); super.onDestroy() }
}