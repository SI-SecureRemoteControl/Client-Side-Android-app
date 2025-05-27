package ba.unsa.etf.si.secureremotecontrol.data.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit // KTX extension for simpler editing

// Define constants for keys
private const val PREFS_NAME = "AppRegistrationPrefs"
private const val KEY_IS_REGISTERED = "is_registered"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_WEBSOCKET_URL = "websocket_url" // New key
// Add other keys if needed (e.g., KEY_REGISTRATION_KEY)

class RegistrationPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isRegistered: Boolean
        get() = prefs.getBoolean(KEY_IS_REGISTERED, false) // Default to false
        set(value) = prefs.edit { putBoolean(KEY_IS_REGISTERED, value) }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null) // Default to null
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var webSocketUrl: String? // New property
        get() = prefs.getString(KEY_WEBSOCKET_URL, null)
        set(value) = prefs.edit { putString(KEY_WEBSOCKET_URL, value) }


    // Function to save registration details together
    fun saveRegistrationDetails(deviceId: String /*, registrationKey: String? = null */) {
        prefs.edit {
            putBoolean(KEY_IS_REGISTERED, true)
            putString(KEY_DEVICE_ID, deviceId)
            // putString(KEY_REGISTRATION_KEY, registrationKey) // If you need to store this too
            apply() // Or commit() if immediate write is critical
        }
    }

    // Function to clear registration details
    fun clearRegistration() {
        prefs.edit {
            remove(KEY_IS_REGISTERED) // or putBoolean(KEY_IS_REGISTERED, false)
            remove(KEY_DEVICE_ID)
            // remove(KEY_REGISTRATION_KEY)
            // Do NOT clear webSocketUrl here unless specifically intended.
            // It's usually set once and kept.
            apply()
        }
    }

    // Optional: A function to clear everything including the URL for testing/reset
    fun clearAllPreferences() {
        prefs.edit {
            clear()
            apply()
        }
    }
}