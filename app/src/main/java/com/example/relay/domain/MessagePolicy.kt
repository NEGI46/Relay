package com.example.relay.domain

data class MessageLimits(
    val maxTtlMillis: Long = 7L * 24 * 60 * 60 * 1_000,
    val clockSkewToleranceMillis: Long = 5L * 60 * 1_000,
    val maxFutureCreatedAtMillis: Long = 24L * 60 * 60 * 1_000,
    val maxHopCount: Int = 16,
    val maxLocationChars: Int = 100,
    val maxNoteChars: Int = 280,
    val maxOtherLabelChars: Int = 50,
)

sealed interface MessageValidation {
    data object Valid : MessageValidation
    data class Invalid(val reason: String) : MessageValidation
}

class MessagePolicy(
    private val clock: Clock,
    val limits: MessageLimits = MessageLimits(),
) {
    fun validate(message: RelayMessage): MessageValidation {
        if (message.messageId.length !in 1..64 || message.originDeviceId.length !in 1..64) {
            return MessageValidation.Invalid("invalid identifier length")
        }
        if (message.createdAt < 0 || message.lifetimeMs !in 1..limits.maxTtlMillis) return MessageValidation.Invalid("invalid lifetime")
        if (message.accumulatedAgeMs !in 0..message.lifetimeMs) return MessageValidation.Invalid("invalid accumulated age")
        if (message.expiresAt < message.createdAt) return MessageValidation.Invalid("legacy expiry precedes creation")
        if (message.lifetimeMs > limits.maxTtlMillis) {
            return MessageValidation.Invalid("ttl exceeds limit")
        }
        if (message.createdAt > clock.nowMillis() + limits.maxFutureCreatedAtMillis) {
            return MessageValidation.Invalid("creation time is too far in the future")
        }
        if (message.hopCount < 0 || message.maxHopCount !in 1..limits.maxHopCount || message.hopCount > message.maxHopCount) {
            return MessageValidation.Invalid("invalid hop count")
        }
        return validatePayload(message)
    }

    fun effectiveAgeMs(message: RelayMessage): Long {
        val base = message.accumulatedAgeMs.coerceIn(0, message.lifetimeMs.coerceAtLeast(0))
        val residence = when {
            message.elapsedRealtimeSessionId == clock.sessionId() && clock.elapsedRealtimeMillis() >= message.receivedElapsedRealtimeMs && message.receivedElapsedRealtimeMs > 0 ->
                clock.elapsedRealtimeMillis() - message.receivedElapsedRealtimeMs
            clock.nowMillis() >= message.persistedAtWallClockMs && message.persistedAtWallClockMs > 0 ->
                clock.nowMillis() - message.persistedAtWallClockMs
            else -> message.lifetimeMs // reboot / unknown baseline: expire rather than extending data
        }
        return saturatingAdd(base, residence, message.lifetimeMs)
    }

    fun isActive(message: RelayMessage): Boolean =
        validate(message) is MessageValidation.Valid && effectiveAgeMs(message) < message.lifetimeMs

    fun canForward(message: RelayMessage): Boolean = isActive(message) && message.hopCount < message.maxHopCount

    fun prepareForTransfer(message: RelayMessage): RelayMessage? {
        if (!canForward(message)) return null
        return message.copy(
            accumulatedAgeMs = effectiveAgeMs(message),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
    }

    /**
     * Prepare a message for PC Gateway upload. Unlike peer forwarding, hop-exhausted
     * messages may still dump to the fixed hub as a final sink while TTL remains valid.
     */
    fun prepareForGatewayUpload(message: RelayMessage): RelayMessage? {
        if (!isActive(message)) return null
        return message.copy(
            accumulatedAgeMs = effectiveAgeMs(message),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
    }

    fun receive(message: RelayMessage): RelayMessage? {
        if (validate(message) !is MessageValidation.Valid || message.accumulatedAgeMs >= message.lifetimeMs || message.hopCount >= message.maxHopCount) return null
        return message.copy(
            hopCount = message.hopCount + 1,
            status = MessageStatus.RECEIVED,
            receivedAt = clock.nowMillis(),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
    }

    private fun validatePayload(message: RelayMessage): MessageValidation {
        val payload = message.payload
        if (message.recordType == RelayRecordType.REPORT && message.messageType == MessageType.SAFETY && payload !is SafetyPayload) {
            return MessageValidation.Invalid("message type and payload disagree")
        }
        if (message.recordType == RelayRecordType.REPORT && message.messageType == MessageType.SUPPLY && payload !is SupplyPayload) {
            return MessageValidation.Invalid("message type and payload disagree")
        }
        if (message.recordType == RelayRecordType.STATUS_CHANGE) {
            val change = payload as? StatusChangePayload ?: return MessageValidation.Invalid("status change payload required")
            if (change.eventId.length !in 1..64 || change.targetMessageId.length !in 1..64 ||
                change.createdBy.length !in 1..64 || change.reason.length > limits.maxNoteChars || change.createdAt < 0
            ) return MessageValidation.Invalid("invalid status change")
            return MessageValidation.Valid
        }
        val location = when (payload) {
            is SafetyPayload -> payload.approximateLocation
            is SupplyPayload -> payload.approximateLocation
            is StatusChangePayload -> return MessageValidation.Invalid("report payload required")
        }
        val note = when (payload) {
            is SafetyPayload -> payload.note
            is SupplyPayload -> payload.note
            is StatusChangePayload -> return MessageValidation.Invalid("report payload required")
        }
        if (location.length > limits.maxLocationChars || note.length > limits.maxNoteChars) {
            return MessageValidation.Invalid("text exceeds limit")
        }
        if (payload is SafetyPayload && payload.companionCount !in 0..99) {
            return MessageValidation.Invalid("invalid companion count")
        }
        if (payload is SupplyPayload) {
            if (payload.requiredCount !in 1..9_999) return MessageValidation.Invalid("invalid required count")
            if ((payload.otherLabel?.length ?: 0) > limits.maxOtherLabelChars) {
                return MessageValidation.Invalid("other label exceeds limit")
            }
            if (payload.kind == SupplyKind.OTHER && payload.otherLabel.isNullOrBlank()) {
                return MessageValidation.Invalid("other supply requires a label")
            }
        }
        return MessageValidation.Valid
    }

    private fun saturatingAdd(base: Long, added: Long, maximum: Long): Long =
        if (added <= 0) base else if (base >= maximum - added) maximum else base + added
}
