package ba.unsa.etf.si.secureremotecontrol.lifecycle // Or your package

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences // Your prefs class
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService // Use the interface
import ba.unsa.etf.si.secureremotecontrol.data.websocket.WebSocketServiceImpl // Needed for specific methods like sendDeregistration if not on interface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton // Make the observer itself a singleton if needed/injected directly
class AppLifecycleObserver @Inject constructor( // Hilt will inject these dependencies
    private val registrationPrefs: RegistrationPreferences,
    private val webSocketService: WebSocketService
) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App came to FOREGROUND.")

        // Check if the device is registered
        if (registrationPrefs.isRegistered) {
            val deviceId = registrationPrefs.deviceId
            if (!deviceId.isNullOrBlank()) {
                // Device is registered, start heartbeat
                Log.i(TAG, "Device registered (ID: $deviceId). Starting heartbeat...")
                try {
                    // 1. Make sure WebSocket is trying to connect
                    // Note: connectWebSocket() might be better called explicitly
                    // when registration happens or if connection drops.
                    // Calling it here ensures an attempt on foregrounding.
                    webSocketService.connectWebSocket()

                    // 2. Start sending heartbeat messages
                    webSocketService.startHeartbeat(deviceId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting WebSocket/Heartbeat", e)
                }
            } else {
                Log.w(TAG, "App is registered but device ID is missing in Preferences!")
                // Maybe clear registration state? registrationPrefs.clearRegistration()
            }
        } else {
            // Device is not registered
            Log.i(TAG, "Device not registered. No heartbeat needed.")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App went to BACKGROUND.")

        // App is not visible, stop heartbeats to save resources/battery
        // We only need to stop if it was potentially started (i.e., if registered)
        if (registrationPrefs.isRegistered) {
            Log.i(TAG, "Stopping heartbeat.")
            try {
                webSocketService.stopHeartbeat()
                // Optional: Disconnect WebSocket completely?
                // webSocketService.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping heartbeat", e)
            }
        }
    }
}