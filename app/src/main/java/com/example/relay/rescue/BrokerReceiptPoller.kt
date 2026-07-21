package com.example.relay.rescue

import android.content.Context
import com.example.relay.RelayApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope

/**
 * Periodically polls the Broker for signed shelter receipts addressed to this device.
 * The deviceKeyId acts as a capability token (not identity-verified).
 * Only SHELTER_* states come from signed receipts via applyReceipt();
 * BROKER_STORED is a separate ledger flag and does NOT advance shelter status.
 */
class BrokerReceiptPoller(
    private val context: Context,
    private val pollIntervalMs: Long = 30_000L,
) {
    private var lastPollEpochMillis: Long = 0L

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

        while (scope.isActive) {
            pollOnce(delivery, app)
            delay(pollIntervalMs)
        }
    }

    /** Single poll iteration. Exposed for testing. */
    suspend fun pollOnce(delivery: BrokerRescueDelivery, app: RelayApplication) {
        val receipts = delivery.pollReceipts(sinceEpochMillis = lastPollEpochMillis)
        if (receipts.isEmpty()) return

        val keys = app.rescueShelterKeyStore.load() ?: return

        for (receipt in receipts) {
            val result = app.rescueRepository.applyReceipt(
                RescueRequestKey(receipt.receipt.requestId, receipt.receipt.requestVersion),
                receipt,
                keys.receiptSigningKey,
            )
            if (result == ReceiptApplicationResult.APPLIED) {
                app.rescueNearbyCoordinator?.onLocalStoreChanged()
            }
            // Track the latest receipt timestamp for incremental polling
            if (receipt.receipt.receivedAtEpochMillis > lastPollEpochMillis) {
                lastPollEpochMillis = receipt.receipt.receivedAtEpochMillis
            }
        }
    }
}
