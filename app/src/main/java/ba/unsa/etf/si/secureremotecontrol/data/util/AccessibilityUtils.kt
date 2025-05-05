package ba.unsa.etf.si.secureremotecontrol.utils

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtils {
    fun isAccessibilityServiceEnabled(context: Context, serviceClassName: String): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val service = "${context.packageName}/$serviceClassName"
        return !TextUtils.isEmpty(enabledServices) && enabledServices.contains(service)
    }
}