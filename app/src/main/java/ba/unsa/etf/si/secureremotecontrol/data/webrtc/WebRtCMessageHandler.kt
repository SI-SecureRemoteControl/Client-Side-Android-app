package ba.unsa.etf.si.secureremotecontrol.data.webrtc

import android.content.Context
import android.content.Intent
import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.ui.ScreenSharingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCMessageHandler @Inject constructor(
    private val context: Context
) {
    private val TAG = "WebRTCMessageHandler"
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "screen_sharing_request" -> {
                    val fromId = json.getString("fromId")
                    handleScreenSharingRequest(fromId)
                }
                // Handle other types of messages
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebRTC message", e)
        }
    }

    private fun handleScreenSharingRequest(fromId: String) {
        coroutineScope.launch {
            val intent = ScreenSharingActivity.getStartIntent(context, fromId)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}