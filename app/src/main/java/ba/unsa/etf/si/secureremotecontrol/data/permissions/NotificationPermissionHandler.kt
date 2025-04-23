import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast

class NotificationPermissionHandler(private val context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    fun checkAndRequestNotificationPermission() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val isAllowed = preferences.getBoolean("notifications_allowed", false)

        if (isAllowed) {
            Toast.makeText(context, "Notifications are already enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!notificationManager.areNotificationsEnabled()) {
            // Notifications are disabled, redirect user to settings
            Toast.makeText(context, "Please enable notifications for this app.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        } else {
            // Save the user's decision as allowed
            preferences.edit().putBoolean("notifications_allowed", true).apply()
            Toast.makeText(context, "Notifications are enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    fun resetPermission() {
        // Call this method if you want to reset the user's decision
        preferences.edit().putBoolean("notifications_allowed", false).apply()
    }
    fun isNotificationPermissionGranted(): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.areNotificationsEnabled()
    }
}