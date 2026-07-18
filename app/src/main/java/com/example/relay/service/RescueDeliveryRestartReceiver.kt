package com.example.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts automatic rescue delivery after boot, app replacement, or Bluetooth becoming available. */
class RescueDeliveryRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> RescueDeliveryService.startIfEnabled(context)
        }
    }
}
