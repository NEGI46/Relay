package com.example.relay.rescue

import android.content.Context
import com.example.relay.RelayApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope

/**
 * Periodically polls the Broker for signed shelter receipts addressed to this device.
 * Uses an unguessable capability token (not the public deviceKeyId).
 * Uses Broker-assigned monotonic seq cursor (not device time) for reliable incremental polling.
 * Only SHELTER_* states come from signed receipts via applyReceipt();
 * BROKER_STORED is a separate ledger flag and does NOT advance shelter status.
 */
class BrokerReceiptPoller(
    private val context: Context,
    private val pollIntervalMs: Long = 30_000L,
) {
    /** Broker monotonic seq cursor. Persisted via SharedPreferences for restart resilience. */
    private var lastSeq: Long = 0L

    /**
     * Starts the polling loop. Call from a coroutine scope.
     * Runs independently of Nearby/BLE/LAN paths.
     */
    suspend fun start(scope: CoroutineScope, app: RelayApplication) {
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return

        val delivery = BrokerRescueDelivery(
            context = context,
            endpoint = endpoint,
            signingKeyStore = app.uploadSigningKeyStore,
        )

        // Load persisted cursor
        lastSeq = context.getSharedPreferences("relay_broker_poller", Context.MODE_PRIVATE)
            .getLong("last_seq", 0L)

        while (scope.isActive) {
            pollOnce(delivery, app)
            delay(pollIntervalMs)
        }
    }

    /** Single poll iteration. Exposed for testing. */
    suspend fun pollOnce(delivery: BrokerRescueDelivery, app: RelayApplication) {
        // A Broker restart, token loss, or Android backup restore can leave the local receipt
        // cursor intact while the registration is gone. Re-establish the capability before
        // polling rather than silently treating that state as an empty receipt batch forever.
        if (!delivery.ensureRegistered()) return
        val batch = delivery.pollReceipts(sinceSeq = lastSeq)
        if (batch.receipts.isEmpty()) return

        val keys = app.rescueShelterKeyStore.load() ?: return

        for (receipt in batch.receipts) {
            val result = app.activeRescueSessionStore.applyVerifiedReceipt(
                RescueRequestKey(receipt.receipt.requestId, receipt.receipt.requestVersion),
                receipt,
                keys.receiptSigningKey,
                System.currentTimeMillis(),
            )
            if (result == ReceiptApplicationResult.APPLIED) {
                app.rescueNearbyCoordinator?.onLocalStoreChanged()
            }
        }

        // Advance cursor to Broker's monotonic seq (not device time)
        if (batch.cursor > lastSeq) {
            lastSeq = batch.cursor
            persistCursor()
        }
    }

    private fun persistCursor() {
        context.getSharedPreferences("relay_broker_poller", Context.MODE_PRIVATE)
            .edit().putLong("last_seq", lastSeq).apply()
    }
}
