package com.example.relay.rescue

import android.content.Context

/** Persistent opt-in set when this device creates or receives a rescue envelope.
 *
 * It deliberately has no user-facing toggle: stopping automatic delivery would
 * strand already accepted emergency data.  The delivery service still fails
 * closed when Android has not granted Bluetooth/foreground-service permission.
 */
interface RescueAutomationStore {
    fun isEnabled(): Boolean
    fun enable()
}

class SharedPreferencesRescueAutomationStore(context: Context) : RescueAutomationStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    override fun enable() {
        preferences.edit().putBoolean(KEY_ENABLED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "relay_rescue_automation"
        const val KEY_ENABLED = "enabled"
    }
}
