package ba.unsa.etf.si.secureremotecontrol.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ba.unsa.etf.si.secureremotecontrol.data.webrtc.WebRTCManager
import ba.unsa.etf.si.secureremotecontrol.service.ScreenSharingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScreenSharingActivity : AppCompatActivity() {

    @Inject
    lateinit var webRTCManager: WebRTCManager

    private val TAG = "ScreenSharingActivity"
    private var screenSharingService: ScreenSharingService? = null
    private var isBound = false
    private var requestingUserId: String? = null

    private val screenCaptureCallback = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val fromId = requestingUserId ?: return@registerForActivityResult

            val serviceIntent = ScreenSharingService.getStartIntent(
                this,
                result.resultCode,
                data,
                fromId
            )
            startService(serviceIntent)
            bindService()

            Log.d(TAG, "Screen capture permission granted")
        } else {
            Log.d(TAG, "Screen capture permission denied")
            Toast.makeText(this, "Screen sharing permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenSharingService.LocalBinder
            screenSharingService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenSharingService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestingUserId = intent.getStringExtra(EXTRA_USER_ID)
        if (requestingUserId == null) {
            Log.e(TAG, "No requesting user ID provided")
            finish()
            return
        }

        webRTCManager.startObservingRtcMessages(this)

        // Request screen capture permission
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureCallback.launch(captureIntent)
    }

    private fun bindService() {
        val intent = Intent(this, ScreenSharingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            bindService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"

        fun getStartIntent(context: Context, userId: String): Intent {
            return Intent(context, ScreenSharingActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId)
            }
        }
    }
}