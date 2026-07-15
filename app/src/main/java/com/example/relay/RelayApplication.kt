package com.example.relay

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.repository.RoomMessageRepository
import com.example.relay.domain.DeviceRoleStore
import com.example.relay.domain.StringDeviceRoleStore
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.SystemClock
import com.example.relay.permissions.AndroidNearbyPermissionGate
import com.example.relay.protocol.FixedWindowIncomingPayloadPolicy
import com.example.relay.protocol.PacketCodec
import com.example.relay.runtime.RelayCommunicationRuntime
import com.example.relay.sync.SyncCoordinator
import com.example.relay.sync.SyncPlanner
import com.example.relay.transport.GoogleNearbyPlatform
import com.example.relay.transport.NearbyConnectionsTransport
import com.example.relay.gateway.GatewayBridgeClient
import com.example.relay.gateway.GatewayCredentialStore
import com.example.relay.gateway.SharedPreferencesGatewayPendingStore
import com.example.relay.gateway.GatewaySettingsStore
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.gateway.HttpGatewayBridgeClient
import com.example.relay.gateway.UdpGatewayDiscovery
import com.google.android.gms.nearby.connection.ConnectionsClient
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RelayApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deviceId: String by lazy {
        getSharedPreferences("relay_identity", MODE_PRIVATE).let { preferences ->
            preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("device_id", it).apply()
            }
        }
    }

    val database: RelayDatabase by lazy {
        Room.databaseBuilder(this, RelayDatabase::class.java, "relay.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
    val messageRepository: RoomMessageRepository by lazy { RoomMessageRepository(database) }

    val deviceRoleStore: DeviceRoleStore by lazy {
        val preferences = getSharedPreferences("relay_settings", MODE_PRIVATE)
        StringDeviceRoleStore(
            readRaw = { preferences.getString("device_role", null) },
            writeRaw = { roleName -> preferences.edit().putString("device_role", roleName).apply() },
        )
    }

    val nearbyPermissionGate by lazy { AndroidNearbyPermissionGate(this) }
    val nearbyTransport: NearbyConnectionsTransport by lazy {
        NearbyConnectionsTransport(
            localDeviceId = deviceId,
            platform = GoogleNearbyPlatform(this, packageName),
            permissionGate = nearbyPermissionGate,
            scope = applicationScope,
            maxPayloadBytes = ConnectionsClient.MAX_BYTES_DATA_SIZE,
        )
    }
    val syncCoordinator: SyncCoordinator by lazy {
        val policy = MessagePolicy(SystemClock)
        SyncCoordinator(
            deviceId = deviceId,
            transport = nearbyTransport,
            repository = messageRepository,
            planner = SyncPlanner(messageRepository, policy),
            policy = policy,
            codec = PacketCodec(policy),
            clock = SystemClock,
            scope = applicationScope,
            incomingPayloadPolicy = FixedWindowIncomingPayloadPolicy(SystemClock),
        )
    }
    val communicationRuntime: RelayCommunicationRuntime by lazy {
        RelayCommunicationRuntime(nearbyTransport, syncCoordinator, applicationScope)
    }
    val gatewaySettingsStore: GatewaySettingsStore by lazy { GatewaySettingsStore(this) }
    val gatewayCredentialStore: GatewayCredentialStore by lazy { GatewayCredentialStore(this) }
    val gatewaySyncEngine: GatewaySyncEngine by lazy {
        GatewaySyncEngine(
            messageRepository,
            gatewaySettingsStore,
            gatewayCredentialStore,
            HttpGatewayBridgeClient(),
            MessagePolicy(SystemClock),
            applicationScope,
            SharedPreferencesGatewayPendingStore(this),
            discovery = UdpGatewayDiscovery(),
            localBridgeId = deviceId,
        )
    }
}

/** Legacy rows get lifetime=0 during migration and are intentionally treated as expired. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN recordType TEXT NOT NULL DEFAULT 'REPORT'")
        db.execSQL("ALTER TABLE messages ADD COLUMN lifetimeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN accumulatedAgeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN receivedElapsedRealtimeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN persistedAtWallClockMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_originDeviceId ON messages(originDeviceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS delivery_receipts (receiptId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, receiptType TEXT NOT NULL, actorId TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_delivery_receipts_messageId_receiptType_actorId ON delivery_receipts(messageId, receiptType, actorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_delivery_receipts_messageId ON delivery_receipts(messageId)")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN elapsedRealtimeSessionId TEXT NOT NULL DEFAULT ''")
    }
}
