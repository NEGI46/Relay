package com.example.relay.domain

data class ResourcePolicy(
    val maxPayloadBytes: Int = 64 * 1024,
    val maxReceivedItemsPerConnection: Int = 256,
    val maxSentBytesPerConnection: Long = 512 * 1024,
    val maxStoredMessages: Int = 2_000,
    val maxStoredMessagesPerOrigin: Int = 300,
)

enum class OperatingMode { NORMAL, DRILL, RELAY }
/**
 * Operational responsibility of this installed Relay device.
 *
 * This is intentionally independent from [OperatingMode]: a device can be a fixed
 * [RELAY] while the user starts a DRILL or RELAY operating mode.
 */
enum class DeviceRole {
    MEMBER,
    COURIER,
    RELAY,
    GATEWAY,
    ADMIN,
}

/** Stable name-based encoding for preferences and future external boundaries. */
object DeviceRoleCodec {
    fun encode(role: DeviceRole): String = role.name

    fun decodeOrDefault(
        value: String?,
        default: DeviceRole = DeviceRole.MEMBER,
    ): DeviceRole = DeviceRole.entries.firstOrNull { it.name == value } ?: default
}

interface DeviceRoleStore {
    fun load(): DeviceRole
    fun save(role: DeviceRole)
}

/**
 * String-backed store that never persists enum ordinals. Unknown or missing values
 * fall back safely to MEMBER instead of crashing application startup.
 */
class StringDeviceRoleStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
) : DeviceRoleStore {
    override fun load(): DeviceRole = DeviceRoleCodec.decodeOrDefault(readRaw())

    override fun save(role: DeviceRole) = writeRaw(DeviceRoleCodec.encode(role))
}

data class RelayRuntimeSettings(
    val mode: OperatingMode = OperatingMode.NORMAL,
    val role: DeviceRole = DeviceRole.MEMBER,
)

/** Future Nearby authentication boundary. No implementation may auto-approve a connection. */
interface ConnectionAuthenticator {
    fun verificationRequired(peerId: String, authenticationDigits: String): ConnectionVerification
}

sealed interface ConnectionVerification {
    data class PendingManualVerification(val peerId: String, val authenticationDigits: String) : ConnectionVerification
    data class Rejected(val reason: String) : ConnectionVerification
}
