package ba.unsa.etf.si.secureremotecontrol.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
// import android.media.projection.MediaProjection // REMOVE THIS IMPORT if not used elsewhere
import android.media.projection.MediaProjectionManager // Keep if needed for checks, but not for getting projection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.activity.viewModels
import androidx.core.app.NotificationCompat
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScreenSharingService : Service() {

    @Inject
    lateinit var webRTCManager: WebRTCManager

    private val TAG = "ScreenSharingService"
    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "screen_sharing_channel"

    private val binder = LocalBinder()
    private var isSharing = false
    private var remoteUserId: String? = null

    // --- REMOVED ---
    // No longer manage MediaProjection here
    // private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = 0 // Still might be useful for state, but not strictly needed for projection here
    private var resultData: Intent? = null // Still might be useful for state, but not strictly needed for projection here
    // --- END REMOVED ---

    inner class LocalBinder : Binder() {
        fun getService(): ScreenSharingService = this@ScreenSharingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_SCREEN_SHARING -> {
                val newResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) // Use RESULT_CANCELED as default
                val newData = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val fromId = intent.getStringExtra(EXTRA_FROM_ID) ?: ""

                // Check for valid result *before* proceeding
                if (newResultCode == Activity.RESULT_OK && newData != null) {
                    // If already sharing, stop the old session FIRST to ensure cleanup
                    if (isSharing) {
                        Log.w(TAG, "Screen sharing already active. Stopping existing session before starting new one.")
                        stopScreenSharingInternal() // Use an internal method to avoid stopping foreground/service yet
                    }

                    // Start foreground service state
                    startForeground(NOTIFICATION_ID, createNotification())

                    // Store results temporarily if needed, but primarily pass them down
                    resultCode = newResultCode
                    resultData = newData // Keep a reference if needed, but WebRTCService will use its copy

                    // Start the actual screen sharing logic
                    startScreenSharing(newResultCode, newData, fromId)
                } else {
                    Log.e(TAG, "Invalid resultCode ($newResultCode) or data is null. Cannot start screen sharing.")
                    stopSelf() // Stop the service if starting parameters are invalid
                }
            }
            ACTION_STOP_SCREEN_SHARING -> {
                stopScreenSharing() // Public method handles stopping foreground and service
            }
        }

        // Use START_NOT_STICKY as screen sharing requires explicit start intent
        return START_NOT_STICKY
    }



    private fun startScreenSharing(resultCode: Int, data: Intent, fromId: String) {
        // No need to check resultCode/data again here, already done in onStartCommand

        remoteUserId = fromId // Store the ID

        try {

            webRTCManager.startScreenCapture(resultCode, data, fromId)
            isSharing = true // Set sharing state AFTER successful start in WebRTCManager (ideally WebRTCManager provides feedback)

            Log.d(TAG, "Screen sharing initiated for user: $fromId")

        } catch (e: Exception) {
            // Catch potential exceptions from webRTCManager.startScreenCapture
            Log.e(TAG, "Failed to start screen sharing via WebRTCManager: ${e.message}", e)
            stopScreenSharing() // Clean up if start fails
            stopSelf() // Ensure service stops if start fails critically
        }
    }

    // Renamed to avoid confusion with public stopScreenSharing
    private fun stopScreenSharingInternal() {
        if (isSharing) {
            Log.d(TAG, "Stopping screen sharing internally...")
            webRTCManager.stopScreenCapture()


            resultData = null // Clear potentially stale intent data
            resultCode = Activity.RESULT_CANCELED

            isSharing = false
            remoteUserId = null
            Log.d(TAG, "Internal screen sharing stop complete.")
        }
    }


    fun stopScreenSharing() {
        Log.d(TAG, "stopScreenSharing called.")
        stopScreenSharingInternal() // Perform the actual stopping logic

        // Stop foreground state and allow service to be stopped if needed
        stopForeground(true)
        stopSelf() // Request the service to stop
        Log.d(TAG, "Screen sharing service stopped.")
    }

    /*override fun onDestroy() {
        Log.d(TAG, "onDestroy called.")
        // Ensure resources are released even if stopScreenSharing wasn't explicitly called
        stopScreenSharingInternal() // Stop capture if still running
        webRTCManager.release() // Release WebRTC resources
        super.onDestroy()
        Log.d(TAG, "ScreenSharingService destroyed.")
    }*/
    // Inside ScreenSharingService.kt
    override fun onDestroy() {
        Log.d("ScreenSharingService", "onDestroy called. Stopping screen capture and releasing WebRTC resources.")
        webRTCManager.stopScreenCapture()
        // Consider if release() is appropriate here. If the service stopping means
        // the whole WebRTC functionality is done until the app restarts, then yes.
        // If the service might restart soon, maybe don't release the factory/EGL yet.
        // webRTCManager.release() // << Be cautious with this here
        super.onDestroy()
    }

    // createNotificationChannel() and createNotification() remain the same

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screen Sharing Active")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_media_play) // Changed icon slightly
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }


    companion object {
        const val ACTION_START_SCREEN_SHARING = "action_start_screen_sharing"
        const val ACTION_STOP_SCREEN_SHARING = "action_stop_screen_sharing"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_FROM_ID = "extra_from_id"

        fun getStartIntent(context: Context, resultCode: Int, data: Intent, fromId: String): Intent {
            return Intent(context, ScreenSharingService::class.java).apply {
                action = ACTION_START_SCREEN_SHARING
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data) // Make sure data is Parcelable or handle serialization if needed
                putExtra(EXTRA_FROM_ID, fromId)
            }
        }

        fun getStopIntent(context: Context): Intent {
            return Intent(context, ScreenSharingService::class.java).apply {
                action = ACTION_STOP_SCREEN_SHARING
            }
        }
    }
}