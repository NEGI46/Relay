package com.example.relay.cloudrelay

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface CloudRelayStore {
    fun put(envelope: EncryptedRescueEnvelope, now: Long): CloudStoredReceipt
    fun pending(shelterId: String, now: Long, limit: Int): List<EncryptedRescueEnvelope>
    fun receipt(deviceId: String, envelopeId: String): SignedShelterReceipt?
}
class PostgresCloudRelayStore(private val jdbcUrl:String, private val user:String, private val password:String):CloudRelayStore {
    init { DriverManager.getConnection(jdbcUrl,user,password).use { c -> c.createStatement().use { it.executeUpdate(
        "CREATE TABLE IF NOT EXISTS relay_envelopes(envelope_id TEXT PRIMARY KEY,request_id TEXT NOT NULL,request_version INT NOT NULL,sender_device_id TEXT NOT NULL,shelter_id TEXT NOT NULL,expires_at BIGINT NOT NULL,ciphertext_hash TEXT NOT NULL,envelope_json TEXT NOT NULL,stored_at BIGINT NOT NULL,UNIQUE(request_id,request_version))"
    ) } } }
    override fun put(e:EncryptedRescueEnvelope,now:Long)=CloudStoredReceipt(UUID.randomUUID().toString(),e.envelopeId,e.requestId,e.requestVersion,e.ciphertextSha256Hex,e.destinationShelterId,now).also { r ->
        DriverManager.getConnection(jdbcUrl,user,password).use { c -> c.prepareStatement("INSERT INTO relay_envelopes VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING").use {
            it.setString(1,e.envelopeId);it.setString(2,e.requestId);it.setInt(3,e.requestVersion);it.setString(4,e.senderDeviceId);it.setString(5,e.destinationShelterId);it.setLong(6,e.expiresAtEpochMillis);it.setString(7,e.ciphertextSha256Hex);it.setString(8,e.toString());it.setLong(9,now);it.executeUpdate()
        } }
    }
    override fun pending(shelterId:String,now:Long,limit:Int)=emptyList<EncryptedRescueEnvelope>()
    override fun receipt(deviceId:String,envelopeId:String):SignedShelterReceipt?=null
}
class InMemoryCloudRelayStore:CloudRelayStore {
    private val envelopes=ConcurrentHashMap<String,EncryptedRescueEnvelope>()
    override fun put(e:EncryptedRescueEnvelope,now:Long)=CloudStoredReceipt(UUID.randomUUID().toString(),e.envelopeId,e.requestId,e.requestVersion,e.ciphertextSha256Hex,e.destinationShelterId,now).also { envelopes.putIfAbsent(e.envelopeId,e) }
    override fun pending(shelterId:String,now:Long,limit:Int)=envelopes.values.filter { it.destinationShelterId==shelterId && it.expiresAtEpochMillis>now }.take(limit)
    override fun receipt(deviceId:String,envelopeId:String):SignedShelterReceipt?=null
}
