package com.example.relay.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.relay.RelayApplication
import com.example.relay.rescue.SharedPreferencesRescueAutomationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Lifecycle owner for automatic rescue delivery.
 *
 * The BLE GATT coordinator is intentionally injected in the next integration
 * step. Keeping its Android lifecycle separate from the legacy Nearby service
 * prevents normal message relay settings from stopping a stored rescue request.
 */
class RescueDeliveryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SharedPreferencesRescueAutomationStore(this).isEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (_: SecurityException) {
            // Android can deny background foreground-service launch. A receiver/
            // worker will retry when the OS next permits it; envelopes remain in Room.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        (application as? RelayApplication)?.rescueDeliveryCoordinator?.start(serviceScope)
        return START_STICKY
    }

    override fun onDestroy() {
        (application as? RelayApplication)?.rescueDeliveryCoordinator?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("Relay が救助要請を運搬中")
        .setContentText("避難所PCを見つけると自動で提出します")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "救助要請の自動運搬", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "relay_rescue_delivery"
        private const val NOTIFICATION_ID = 1002

        fun startIfEnabled(context: Context) {
            if (!SharedPreferencesRescueAutomationStore(context).isEnabled()) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RescueDeliveryService::class.java)) }
        }

        fun enableAndStart(context: Context) {
            SharedPreferencesRescueAutomationStore(context).enable()
            startIfEnabled(context)
        }
    }
}
