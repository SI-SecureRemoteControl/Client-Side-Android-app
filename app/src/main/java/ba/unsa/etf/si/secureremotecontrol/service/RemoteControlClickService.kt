/*package ba.unsa.etf.si.secureremotecontrol.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.util.WebSocketServiceHolder
import ba.unsa.etf.si.secureremotecontrol.service.RemoteControlAccessibilityService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class RemoteControlClickService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var clickJob: Job? = null
    private val TAG = "RemoteControlClickService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting click listener service...")

        val webSocketService = WebSocketServiceHolder.instance

        if (webSocketService == null) {
            Log.e(TAG, "WebSocketService is null, cannot start observing clicks.")
            stopSelf()
            return START_NOT_STICKY
        }

        clickJob = webSocketService.observeClickEvents()
            .onEach { (x, y) ->
                Log.d(TAG, "Received click at: ($x, $y)")
                RemoteControlAccessibilityService.instance?.performClick(x, y)
            }
            .launchIn(scope)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Stopping click listener service...")
        clickJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}*/
package ba.unsa.etf.si.secureremotecontrol.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.websocket.WebSocketServiceImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class RemoteControlClickService : Service() {

    @Inject
    lateinit var webSocketService: WebSocketServiceImpl

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("RemoteControlClickService", "Service started")
        observeSwipeEvents()
        return START_STICKY
    }

    private fun observeSwipeEvents() {
        serviceScope.launch {
            try {
                webSocketService.observeSwipeEvents().collect { (start, end, duration) ->
                    RemoteControlAccessibilityService.instance?.performSwipe(
                        start.first, start.second, end.first, end.second, duration
                    )
                    Log.d(
                        "RemoteControlClickService",
                        "Swipe event processed: Start=($start), End=($end), Duration=$duration"
                    )
                }
            } catch (e: Exception) {
                Log.e("RemoteControlClickService", "Error observing swipe events: ${e.message}", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RemoteControlClickService", "Service destroyed")
        serviceScope.cancel() // Cancel all coroutines when the service is destroyed
    }
}
