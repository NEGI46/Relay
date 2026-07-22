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
import com.example.relay.domain.OperatingMode
import com.example.relay.rescue.DevelopmentEnrollmentResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.SharedPreferencesRescueAutomationStore
import com.example.relay.rescue.BrokerDeliveryResult
import com.example.relay.rescue.BrokerReceiptPoller
import com.example.relay.rescue.BrokerRescueDelivery
import com.example.relay.rescue.BrokerRetryWorker
import com.example.relay.rescue.HttpShelterGatewayDelivery
import com.example.relay.rescue.GatewayDeliveryResult
import com.example.relay.rescue.ble.SharedPreferencesCourierDeliveryIdStore
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.rank
import com.example.relay.data.local.BrokerLedgerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle owner for automatic rescue delivery. */
class RescueDeliveryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusMonitorJob: Job? = null
    private var localGatewayDeliveryJob: Job? = null
    private var brokerDeliveryJob: Job? = null
    private var brokerReceiptPollJob: Job? = null
    private var destinationResolutionJob: Job? = null

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
            (application as? RelayApplication)?.diagnostics?.record("rescue_foreground_security_exception")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        (application as? RelayApplication)?.let { app ->
            app.diagnostics.record("rescue_delivery_started")
            startNearbyRelayForRescue(app)
            app.rescueDeliveryCoordinator.start(serviceScope)
            startDestinationResolution(app)
            startLocalGatewayDelivery(app)
            startBrokerDelivery(app)
            startBrokerReceiptPolling(app)
            monitorOwnRequestStatus(app)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as? RelayApplication)?.diagnostics?.record("rescue_delivery_destroyed")
        (application as? RelayApplication)?.rescueDeliveryCoordinator?.stop()
        localGatewayDeliveryJob?.cancel()
        brokerDeliveryJob?.cancel()
        brokerReceiptPollJob?.cancel()
        destinationResolutionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("Relay が救助要請を中継中")
        .setContentText("地域の救助拠点が見つかると中継します")
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

    /**
     * A rescue request owns the same Nearby transport as ordinary relay mode. This works without
     * Wi-Fi or mobile data; Android permission/Bluetooth prerequisites still remain explicit OS
     * requirements and are reported by [RelayCommunicationService].
     */
    private fun startNearbyRelayForRescue(app: RelayApplication) {
        RelayCommunicationService.start(this, OperatingMode.RELAY, app.deviceRoleStore.load())?.let {
            getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
                .putString("last_nearby_start_result", "failed")
                .putLong("last_nearby_start_at", System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * A no-key SOS remains only under the sender's recovery cipher. Development builds may
     * discover their local generated-key Gateway here; production never auto-enrolls and simply
     * waits for a normal trusted provisioning source to populate the key store.
     */
    private fun startDestinationResolution(app: RelayApplication) {
        if (destinationResolutionJob?.isActive == true) return
        destinationResolutionJob = serviceScope.launch {
            while (isActive) {
                val hasPendingDestination = app.activeRescueSessionCoordinator.hasPendingDestination()
                if (hasPendingDestination && app.rescueShelterKeyStore.load() == null) {
                    val enrollment = app.developmentShelterManifestBootstrap.tryEnroll()
                    if (enrollment !in setOf(
                            DevelopmentEnrollmentResult.DISABLED,
                            DevelopmentEnrollmentResult.GATEWAY_NOT_FOUND,
                        )
                    ) {
                        getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
                            .putString("last_development_enrollment", enrollment.name)
                            .putLong("last_development_enrollment_at", System.currentTimeMillis())
                            .apply()
                    }
                }
                val resolved = if (hasPendingDestination) {
                    runCatching { app.activeRescueSessionCoordinator.resolvePendingDestinations() }.getOrDefault(0)
                } else {
                    0
                }
                if (resolved > 0) app.notifyRescueStoreChanged()
                delay(DESTINATION_RESOLUTION_INTERVAL_MILLIS)
            }
        }
    }

    private fun startLocalGatewayDelivery(app: RelayApplication) {
        if (localGatewayDeliveryJob?.isActive == true) return
        localGatewayDeliveryJob = serviceScope.launch {
            // Share the application discovery socket/multicast lock with normal Gateway sync.
            val delivery = HttpShelterGatewayDelivery(app.gatewayDiscovery)
            val deliveryIds = SharedPreferencesCourierDeliveryIdStore(app)
            while (isActive) {
                val candidate = selectLocalGatewayCandidate(
                    app.rescueRepository.all(),
                    System.currentTimeMillis(),
                )
                if (candidate != null) {
                    when (val result = delivery.deliver(
                        candidate.envelope,
                        app.deviceId,
                        deliveryIds.idFor(RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion)),
                    )) {
                    is GatewayDeliveryResult.Accepted -> {
                        val keys = app.rescueShelterKeyStore.load()
                        if (keys != null) {
                            val applied = app.activeRescueSessionStore.applyVerifiedReceipt(
                                RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion),
                                result.receipt,
                                keys.receiptSigningKey,
                                System.currentTimeMillis(),
                            )
                            if (applied == com.example.relay.rescue.ReceiptApplicationResult.APPLIED) {
                                app.rescueNearbyCoordinator?.onLocalStoreChanged()
                            }
                        }
                    }
                    else -> recordDeliveryDiagnostic(result)
                    }
                }
                delay(2_000)
            }
        }
    }

    /**
     * Broker delivery runs independently of LAN/Nearby/BLE.
     * On failure, enqueues a unique OneTime WorkManager retry.
     * Does NOT increment hopCount.
     */
    private fun startBrokerDelivery(app: RelayApplication) {
        if (brokerDeliveryJob?.isActive == true) return
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return

        brokerDeliveryJob = serviceScope.launch {
            val delivery = BrokerRescueDelivery(
                context = this@RescueDeliveryService,
                endpoint = endpoint,
                signingKeyStore = app.uploadSigningKeyStore,
            )
            val dao = app.database.brokerLedgerDao()
            while (isActive) {
                val candidates = app.rescueRepository.all()
                    .filter { it.envelope.expiresAtEpochMillis > System.currentTimeMillis() }
                    .filter {
                        it.state.submissionStatus in setOf(
                            RescueSubmissionStatus.PENDING,
                            RescueSubmissionStatus.IN_TRANSIT,
                        )
                    }
                for (record in candidates) {
                    val ledger = dao.find(record.envelope.requestId, record.envelope.requestVersion)
                    if (ledger?.brokerStatus in setOf("UPLOADED", "FAILED")) continue

                    // Ensure ledger entry exists
                    if (ledger == null) {
                        dao.upsert(BrokerLedgerEntity(
                            requestId = record.envelope.requestId,
                            requestVersion = record.envelope.requestVersion,
                            brokerReceiptId = null,
                            brokerStatus = "PENDING",
                            uploadedAtEpochMillis = null,
                        ))
                    }

                    when (val result = delivery.deliver(record.envelope)) {
                        is BrokerDeliveryResult.Stored -> {
                            app.diagnostics.record("broker_envelope_stored")
                            dao.markUploaded(
                                requestId = record.envelope.requestId,
                                requestVersion = record.envelope.requestVersion,
                                receiptId = result.response.brokerReceiptId,
                                uploadedAt = result.response.storedAtEpochMillis,
                            )
                        }
                        is BrokerDeliveryResult.Offline -> {
                            app.diagnostics.record("broker_offline_retry")
                            dao.markRetrying(record.envelope.requestId, record.envelope.requestVersion)
                            BrokerRetryWorker.enqueue(
                                this@RescueDeliveryService,
                                record.envelope.requestId,
                                record.envelope.requestVersion,
                            )
                        }
                        is BrokerDeliveryResult.Failed -> {
                            app.diagnostics.record(if (result.retryable) "broker_retryable_failure" else "broker_nonretryable_failure")
                            if (result.retryable) {
                                dao.markRetrying(record.envelope.requestId, record.envelope.requestVersion)
                                BrokerRetryWorker.enqueue(
                                    this@RescueDeliveryService,
                                    record.envelope.requestId,
                                    record.envelope.requestVersion,
                                )
                            } else {
                                dao.markFailed(record.envelope.requestId, record.envelope.requestVersion)
                            }
                        }
                        is BrokerDeliveryResult.Disabled -> app.diagnostics.record("broker_disabled")
                    }
                }
                delay(10_000)
            }
        }
    }

    /**
     * Polls Broker for signed shelter receipts.
     * Only SHELTER_* states come from signed receipts via applyReceipt().
     */
    private fun startBrokerReceiptPolling(app: RelayApplication) {
        if (brokerReceiptPollJob?.isActive == true) return
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return

        brokerReceiptPollJob = serviceScope.launch {
            val poller = BrokerReceiptPoller(this@RescueDeliveryService)
            poller.start(this, app)
        }
    }

    private fun recordDeliveryDiagnostic(result: GatewayDeliveryResult) {
        getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
            .putString("last_delivery_result", result.javaClass.simpleName)
            .putLong("last_delivery_at", System.currentTimeMillis())
            .apply()
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
        private const val DESTINATION_RESOLUTION_INTERVAL_MILLIS = 2_000L

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

internal fun selectLocalGatewayCandidate(
    records: List<StoredRescueRecord>,
    nowEpochMillis: Long,
): StoredRescueRecord? = records.asSequence()
    .filter { it.envelope.expiresAtEpochMillis > nowEpochMillis }
    .filter {
        it.state.submissionStatus in setOf(
            RescueSubmissionStatus.PENDING,
            RescueSubmissionStatus.IN_TRANSIT,
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
        )
    }
    .sortedWith(
        compareByDescending<StoredRescueRecord> { it.state.submissionStatus.deliveryPriority() }
            .thenByDescending { it.envelope.routingUrgency.deliveryPriority() }
            .thenByDescending { it.envelope.createdAtEpochMillis },
    )
    .firstOrNull()

private fun RescueSubmissionStatus.deliveryPriority(): Int = when (this) {
    RescueSubmissionStatus.PENDING -> 3
    RescueSubmissionStatus.IN_TRANSIT -> 2
    RescueSubmissionStatus.SHELTER_STORED -> 1
    else -> 0
}

private fun RescueUrgency.deliveryPriority(): Int = when (this) {
    RescueUrgency.IMMEDIATE -> 3
    RescueUrgency.URGENT -> 2
    RescueUrgency.ROUTINE -> 1
}
