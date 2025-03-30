package ba.unsa.etf.si.secureremotecontrol.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import ba.unsa.etf.si.secureremotecontrol.data.repository.DeviceRepository
import ba.unsa.etf.si.secureremotecontrol.data.repository.SessionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

@AndroidEntryPoint
class RemoteControlService : Service() {
    
    @Inject
    lateinit var deviceRepository: DeviceRepository
    
    @Inject
    lateinit var sessionRepository: SessionRepository
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        // Initialize WebSocket connection and other necessary components
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle start command
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
} 