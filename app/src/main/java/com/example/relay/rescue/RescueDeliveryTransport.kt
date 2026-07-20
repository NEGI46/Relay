package com.example.relay.rescue
interface RescueDeliveryTransport { suspend fun deliver(envelope: EncryptedRescueEnvelope): RescueTransportResult }
sealed interface RescueTransportResult {
 data class Stored(val receipt: CloudStoredReceipt): RescueTransportResult
 data object Offline: RescueTransportResult
 data class Failed(val reason:String): RescueTransportResult
}
