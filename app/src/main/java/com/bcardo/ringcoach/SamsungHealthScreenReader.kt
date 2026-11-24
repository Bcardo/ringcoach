package com.bcardo.ringcoach

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/** Inactive compatibility component preserving an existing owner-enabled binding.
 * Screen capture/navigation has been retired; this service never reads windows or opens apps.
 */
class SamsungHealthScreenReader : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        RecoveryReliability.observe(this, "service_connected")
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        RecoveryReliability.observe(this, "service_destroyed")
        super.onDestroy()
    }
    override fun onInterrupt() { }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    companion object {
        @Volatile private var instance: SamsungHealthScreenReader? = null
        val enabled: Boolean get() = instance != null
        fun configured(context: Context): Boolean = Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {
            ComponentName.unflattenFromString(it)?.let { component ->
                component.packageName == context.packageName && component.className == SamsungHealthScreenReader::class.java.name
            } == true
        }
    }
}
