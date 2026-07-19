package com.example.relay

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.local.PlaintextDatabaseMigration
import com.example.relay.data.repository.RoomMessageRepository
import com.example.relay.data.repository.RoomRescueEnvelopeRepository
import com.example.relay.data.local.SqlCipherPassphraseStore
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
import com.example.relay.gateway.SharedPreferencesGatewayDeliveryLedger
import com.example.relay.gateway.GatewaySettingsStore
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.gateway.HttpGatewayBridgeClient
import com.example.relay.gateway.UdpGatewayDiscovery
import com.example.relay.cloud.AndroidNetworkOnlineDetector
import com.example.relay.cloud.InternetPrioritySync
import com.example.relay.cloud.HttpsPriorityMessageSource
import com.example.relay.cloud.InternetPrioritySyncScheduler
import com.example.relay.cloud.SharedPreferencesPriorityFeedConfigStore
import com.example.relay.cloud.ServerSyncGateway
import com.example.relay.location.AndroidLocationProvider
import com.example.relay.location.LocationProvider
import com.example.relay.service.CommunicationSupervisor
import com.example.relay.service.RescueDeliveryService
import com.example.relay.rescue.RescueShelterKeyStore
import com.example.relay.rescue.ReportSigningKeyStore
import com.example.relay.rescue.DebugShelterManifestBootstrap
import com.example.relay.rescue.HttpShelterManifestClient
import com.example.relay.rescue.ShelterManifestEnrollment
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.ble.AndroidShelterBleClient
import com.example.relay.rescue.ble.SharedPreferencesCourierDeliveryIdStore
import com.example.relay.rescue.ble.ShelterDeliveryCoordinator
import com.example.relay.rescue.nearby.RescueNearbyCoordinator
import com.google.android.gms.nearby.connection.ConnectionsClient
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        System.loadLibrary("sqlcipher")
        val passphrase = SqlCipherPassphraseStore(this).loadOrCreate()
        PlaintextDatabaseMigration.migrateIfNeeded(this, DATABASE_NAME, passphrase)
        Room.databaseBuilder(this, RelayDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
    val messageRepository: RoomMessageRepository by lazy { RoomMessageRepository(database) }
    val rescueRepository: RoomRescueEnvelopeRepository by lazy { RoomRescueEnvelopeRepository(database) }
    val rescueShelterKeyStore: RescueShelterKeyStore by lazy { RescueShelterKeyStore(this) }
    val reportSigningKeyStore: ReportSigningKeyStore by lazy { ReportSigningKeyStore() }
    val shelterManifestEnrollment: ShelterManifestEnrollment by lazy {
        ShelterManifestEnrollment(HttpShelterManifestClient(), rescueShelterKeyStore)
    }
    /** Populated by signed regional provisioning; empty configuration fails closed. */
    val regionalShelterDirectoryResolver: RegionalShelterDirectoryResolver by lazy {
        RegionalShelterDirectoryResolver(emptyList())
    }
    val rescueDeliveryCoordinator: ShelterDeliveryCoordinator by lazy {
        ShelterDeliveryCoordinator(
            client = AndroidShelterBleClient(this),
            repository = rescueRepository,
            directoryResolver = regionalShelterDirectoryResolver,
            carrierId = deviceId,
            deliveryIds = SharedPreferencesCourierDeliveryIdStore(this),
        )
    }

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
    /**
     * Uses the existing Nearby transport only after it is available. A construction failure leaves
     * the ordinary relay coordinator operational; rescue envelopes remain safely persisted for a
     * later retry rather than being downgraded or exposed.
     */
    val rescueNearbyCoordinator: RescueNearbyCoordinator? by lazy {
        runCatching {
            RescueNearbyCoordinator(
                repository = rescueRepository,
                transport = nearbyTransport,
                nowEpochMillis = SystemClock::nowMillis,
                shelterKeyProvider = rescueShelterKeyStore,
            )
        }.getOrNull()
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
            rescueNearbyCoordinator = rescueNearbyCoordinator,
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
            SharedPreferencesGatewayDeliveryLedger(this),
            discovery = UdpGatewayDiscovery(),
            localBridgeId = deviceId,
        )
    }
    val communicationSupervisor: CommunicationSupervisor by lazy {
        CommunicationSupervisor(communicationRuntime, gatewaySyncEngine, internetPrioritySyncScheduler)
    }

    val locationProvider: LocationProvider by lazy { AndroidLocationProvider(this) }

    /** When online, merge priority remote messages into local Room store. */
    val internetPrioritySync: ServerSyncGateway by lazy {
        InternetPrioritySync(
            detector = AndroidNetworkOnlineDetector(this),
            source = HttpsPriorityMessageSource(SharedPreferencesPriorityFeedConfigStore(this)),
            repository = messageRepository,
            policy = MessagePolicy(SystemClock),
        )
    }
    val internetPrioritySyncScheduler: InternetPrioritySyncScheduler by lazy {
        InternetPrioritySyncScheduler(
            gateway = internetPrioritySync,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            applicationScope.launch {
                DebugShelterManifestBootstrap(
                    discovery = UdpGatewayDiscovery(),
                    client = HttpShelterManifestClient(),
                    loadExisting = rescueShelterKeyStore::load,
                    saveManifest = rescueShelterKeyStore::saveVerifiedManifest,
                ).enrollFromLocalTestGateway()
            }
        }
        // A process restart must not require a courier to open a transfer screen.
        RescueDeliveryService.startIfEnabled(this)
    }

    private companion object {
        const val DATABASE_NAME = "relay.db"
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

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS rescue_envelopes (
                requestId TEXT NOT NULL,
                requestVersion INTEGER NOT NULL,
                envelopeId TEXT NOT NULL,
                ciphertextSha256Hex TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                expiresAtEpochMillis INTEGER NOT NULL,
                storageSizeBytes INTEGER NOT NULL,
                envelopeJson TEXT NOT NULL,
                receivedAtEpochMillis INTEGER NOT NULL,
                submissionStatus TEXT NOT NULL,
                submissionCount INTEGER NOT NULL,
                signedReceiptJson TEXT,
                PRIMARY KEY(requestId, requestVersion)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_rescue_envelopes_expiresAtEpochMillis " +
                "ON rescue_envelopes(expiresAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_rescue_envelopes_submissionStatus " +
                "ON rescue_envelopes(submissionStatus)",
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reportSignatureJson TEXT")
    }
}
