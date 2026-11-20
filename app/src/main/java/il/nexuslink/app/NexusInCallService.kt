package il.nexuslink.app

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.os.Handler
import android.os.Looper

/**
 * Telecom/HFP integration point.
 *
 * This service receives calls only when the Android device firmware exposes the
 * remote phone as a Telecom PhoneAccount and the user chooses NexusLink as dialer.
 */
object CallController {
    @Volatile var activeCall: Call? = null
        private set
    @Volatile private var inCallService: NexusInCallService? = null
    @Volatile var currentAudioRoute: Int = CallAudioState.ROUTE_EARPIECE
        private set
    @Volatile var observer: (() -> Unit)? = null

    fun attachService(service: NexusInCallService) {
        inCallService = service
        notifyChanged()
    }

    fun detachService(service: NexusInCallService) {
        if (inCallService === service) inCallService = null
    }

    fun attachCall(call: Call) {
        activeCall = call
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = notifyChanged()
            override fun onDetailsChanged(call: Call, details: Call.Details) = notifyChanged()
        })
        notifyChanged()
    }

    fun detachCall(call: Call) {
        if (activeCall === call) activeCall = null
        notifyChanged()
    }

    fun answer() {
        try { activeCall?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) } catch (_: Exception) {}
    }

    fun reject() {
        try { activeCall?.reject(false, null) } catch (_: Exception) {}
    }

    fun hangUp() {
        try { activeCall?.disconnect() } catch (_: Exception) {}
    }

    fun toggleSpeaker() {
        val svc = inCallService ?: return
        val next = if (currentAudioRoute == CallAudioState.ROUTE_SPEAKER)
            CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        try { svc.setAudioRoute(next) } catch (_: Exception) {}
    }

    fun setAudioRoute(route: Int) {
        currentAudioRoute = route
        notifyChanged()
    }

    fun sendDtmf(digit: Char) {
        val c = activeCall ?: return
        try {
            c.playDtmfTone(digit)
            Handler(Looper.getMainLooper()).postDelayed({
                try { c.stopDtmfTone() } catch (_: Exception) {}
            }, 180)
        } catch (_: Exception) {}
    }

    fun stateText(): String {
        return when (activeCall?.state) {
            Call.STATE_RINGING -> "שיחה נכנסת"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "מתקשר…"
            Call.STATE_ACTIVE -> "בשיחה"
            Call.STATE_HOLDING -> "שיחה בהמתנה"
            Call.STATE_DISCONNECTING -> "מסיים שיחה…"
            Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
            else -> "אין שיחה פעילה"
        }
    }

    fun number(): String = activeCall?.details?.handle?.schemeSpecificPart.orEmpty()

    private fun notifyChanged() {
        Handler(Looper.getMainLooper()).post { observer?.invoke() }
    }
}

class NexusInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        CallController.attachService(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallController.attachCall(call)
    }

    override fun onCallRemoved(call: Call) {
        CallController.detachCall(call)
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallController.setAudioRoute(audioState.route)
    }

    override fun onDestroy() {
        CallController.detachService(this)
        super.onDestroy()
    }
}
