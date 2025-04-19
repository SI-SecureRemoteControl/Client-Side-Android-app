package ba.unsa.etf.si.secureremotecontrol.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
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

    // Keep track of the MediaProjection to prevent reuse
    private var mediaProjection: MediaProjection? = null
    private var resultCode: Int = 0
    private var resultData: Intent? = null

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
                val newResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val newData = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val fromId = intent.getStringExtra(EXTRA_FROM_ID) ?: ""

                if (newData != null) {
                    // Handle the case where we already have an active sharing session
                    if (isSharing) {
                        Log.d(TAG, "Screen sharing is already in progress. Stopping existing session.")
                    }
                    stopScreenSharing()
                    // Start new session with fresh token
                    startForeground(NOTIFICATION_ID, createNotification())
                    resultCode = newResultCode
                    resultData = newData
                    startScreenSharing(newResultCode, newData, fromId)
                } else {
                    Log.e(TAG, "Intent data is null, cannot start screen sharing")
                    stopSelf()
                }
            }
            ACTION_STOP_SCREEN_SHARING -> {
                stopScreenSharing()
                stopForeground(true)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startScreenSharing(resultCode: Int, data: Intent, fromId: String) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid resultCode or data. Cannot start screen sharing.")
            stopSelf()
            return
        }

        remoteUserId = fromId

        try {
            // Get a new MediaProjection instance
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection instance")
                stopSelf()
                return
            }

            // Pass the fresh MediaProjection to WebRTCManager
            webRTCManager.startScreenCapture(resultCode, data, fromId)
            isSharing = true
            Log.d(TAG, "Screen sharing started with user: $fromId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen sharing: ${e.message}", e)
            stopSelf()
        }
    }

    fun stopScreenSharing() {
        if (isSharing) {
            webRTCManager.stopScreenCapture()

            // Release the MediaProjection to prevent token reuse
            mediaProjection?.stop()
            mediaProjection = null
            resultData = null

            isSharing = false
            Log.d(TAG, "Screen sharing stopped")
        }
    }

    override fun onDestroy() {
        stopScreenSharing()
        webRTCManager.release()
        super.onDestroy()
    }

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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
                putExtra(EXTRA_DATA, data)
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