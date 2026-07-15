package com.example.relay.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageType { SAFETY, SUPPLY }

@Serializable
enum class RelayRecordType { REPORT, STATUS_CHANGE }

@Serializable
enum class ReportStatus { ACTIVE, RESOLVED, RETRACTED }

@Serializable
enum class MessagePriority { LOW, NORMAL, HIGH, CRITICAL }

@Serializable
enum class MessageStatus { CREATED, RECEIVED }

@Serializable
enum class SafetyState { SAFE, INJURED, EVACUATING, AT_SHELTER }

@Serializable
enum class SupplyKind { WATER, FOOD, MEDICINE, BLANKET, POWER, HYGIENE, OTHER }

@Serializable
sealed interface MessagePayload

@Serializable
@SerialName("safety")
data class SafetyPayload(
    val state: SafetyState,
    val companionCount: Int,
    val approximateLocation: String,
    val note: String,
) : MessagePayload

@Serializable
@SerialName("supply")
data class SupplyPayload(
    val kind: SupplyKind,
    val requiredCount: Int,
    val approximateLocation: String,
    val note: String,
    val otherLabel: String? = null,
) : MessagePayload

@Serializable
@SerialName("status_change")
data class StatusChangePayload(
    val eventId: String,
    val targetMessageId: String,
    val newStatus: ReportStatus,
    val reason: String,
    val createdAt: Long,
    val createdBy: String,
) : MessagePayload

@Serializable
data class RelayMessage(
    val messageId: String,
    val messageType: MessageType,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: MessagePriority,
    val originDeviceId: String,
    val payload: MessagePayload,
    val hopCount: Int = 0,
    val maxHopCount: Int = 8,
    val status: MessageStatus,
    val receivedAt: Long,
    val recordType: RelayRecordType = RelayRecordType.REPORT,
    /** Authoritative relay lifetime; expiresAt is retained only as legacy display metadata. */
    val lifetimeMs: Long = (expiresAt - createdAt).coerceAtLeast(0),
    /** Age accumulated by prior devices, never reset at a new receiver. */
    val accumulatedAgeMs: Long = 0,
    /** Local monotonic-clock baseline, meaningful only on the device that persisted it. */
    val receivedElapsedRealtimeMs: Long = 0,
    /** Local wall-clock persistence point used conservatively across reboot. */
    val persistedAtWallClockMs: Long = receivedAt,
    val elapsedRealtimeSessionId: String = "",
)

@Serializable
enum class ReceiptType { PEER_RECEIVED, GATEWAY_RECEIVED, GATEWAY_RECEIVED_UNVERIFIED }

@Serializable
data class DeliveryReceipt(
    val receiptId: String,
    val messageId: String,
    val receiptType: ReceiptType,
    val actorId: String,
    val recordedAt: Long,
)

data class PayloadTransfer(
    val messageId: String,
    val peerId: String,
    val packetId: String,
    val completedAt: Long,
)

enum class DeliveryPresentation { NOT_CONFIRMED, NEARBY_PAYLOAD_COMPLETE, PEER_RECEIVED, GATEWAY_RECEIVED_UNVERIFIED, GATEWAY_RECEIVED }

fun deriveDeliveryPresentation(receipts: Collection<DeliveryReceipt>, payloadTransferred: Boolean = false): DeliveryPresentation = when {
    receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED } -> DeliveryPresentation.GATEWAY_RECEIVED
    receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED } -> DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED
    receipts.any { it.receiptType == ReceiptType.PEER_RECEIVED } -> DeliveryPresentation.PEER_RECEIVED
    payloadTransferred -> DeliveryPresentation.NEARBY_PAYLOAD_COMPLETE
    else -> DeliveryPresentation.NOT_CONFIRMED
}
