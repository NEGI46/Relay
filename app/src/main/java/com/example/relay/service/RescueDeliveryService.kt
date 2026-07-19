package com.example.relay.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.relay.MainActivity
import com.example.relay.RelayApplication
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.SharedPreferencesRescueAutomationStore
import com.example.relay.rescue.HttpShelterGatewayDelivery
import com.example.relay.rescue.ble.SharedPreferencesCourierDeliveryIdStore
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.rank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle owner for automatic rescue delivery.
 *
 * The BLE GATT coordinator is intentionally injected in the next integration
 * step. Keeping its Android lifecycle separate from the legacy Nearby service
 * prevents normal message relay settings from stopping a stored rescue request.
 */
class RescueDeliveryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusMonitorJob: Job? = null
    private var localGatewayDeliveryJob: Job? = null

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
        (application as? RelayApplication)?.let { app ->
            app.rescueDeliveryCoordinator.start(serviceScope)
            startLocalGatewayDelivery(app)
            monitorOwnRequestStatus(app)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as? RelayApplication)?.rescueDeliveryCoordinator?.stop()
        localGatewayDeliveryJob?.cancel()
        serviceScope.cancel()
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
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL_ID, "救助要請の自動運搬", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(STATUS_CHANNEL_ID, "救助要請の対応状況", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun monitorOwnRequestStatus(app: RelayApplication) {
        if (statusMonitorJob?.isActive == true) return
        statusMonitorJob = serviceScope.launch {
            val preferences = getSharedPreferences("relay_rescue_status_notifications", MODE_PRIVATE)
            while (isActive) {
                val own = app.rescueRepository.all()
                    .filter { it.envelope.senderDeviceId == app.deviceId }
                    .maxByOrNull { it.envelope.requestVersion }
                if (own != null && own.state.submissionStatus.rank() >= RescueSubmissionStatus.SHELTER_ACCEPTED.rank()) {
                    val storageKey = "${own.envelope.requestId}:${own.envelope.requestVersion}"
                    val previous = preferences.getString(storageKey, null)
                    val current = own.state.submissionStatus.name
                    if (previous != current) {
                        preferences.edit().putString(storageKey, current).apply()
                        notifyStatus(own.state.submissionStatus)
                    }
                }
                delay(5_000)
            }
        }
    }

    private fun startLocalGatewayDelivery(app: RelayApplication) {
        if (localGatewayDeliveryJob?.isActive == true) return
        localGatewayDeliveryJob = serviceScope.launch {
            val delivery = HttpShelterGatewayDelivery()
            val deliveryIds = SharedPreferencesCourierDeliveryIdStore(app)
            while (isActive) {
                val candidate = app.rescueRepository.all()
                    .asSequence()
                    .filter { it.envelope.expiresAtEpochMillis > System.currentTimeMillis() }
                    .filter {
                        it.state.submissionStatus in setOf(
                            RescueSubmissionStatus.PENDING,
                            RescueSubmissionStatus.IN_TRANSIT,
                            RescueSubmissionStatus.SHELTER_STORED,
                        )
                    }
                    .sortedWith(compareByDescending { it.envelope.routingUrgency.name })
                    .firstOrNull()
                if (candidate != null) {
                    val receipt = delivery.deliver(
                        candidate.envelope,
                        app.deviceId,
                        deliveryIds.idFor(RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion)),
                    )
                    if (receipt != null) {
                        val keys = app.rescueShelterKeyStore.load()
                        if (keys != null) {
                            app.rescueRepository.applyReceipt(
                                RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion),
                                receipt,
                                keys.receiptSigningKey,
                            )
                        }
                    }
                }
                delay(5_000)
            }
        }
    }

    private fun notifyStatus(status: RescueSubmissionStatus) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (status) {
            RescueSubmissionStatus.SHELTER_ACCEPTED -> "避難所が救助依頼を確認しました"
            RescueSubmissionStatus.SHELTER_RESPONDING -> "避難所が対応中です"
            RescueSubmissionStatus.SHELTER_COMPLETED -> "対応完了の連絡を受信しました"
            RescueSubmissionStatus.CANCELLED -> "取消を避難所が確認しました"
            RescueSubmissionStatus.SHELTER_REJECTED -> "避難所で確認が必要です"
            else -> return
        }
        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Relay 救助依頼の更新")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        getSystemService(NotificationManager::class.java).notify(STATUS_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "relay_rescue_delivery"
        private const val STATUS_CHANNEL_ID = "relay_rescue_status"
        private const val NOTIFICATION_ID = 1002
        private const val STATUS_NOTIFICATION_ID = 1003

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
