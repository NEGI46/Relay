package com.example.relay.gateway

import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.MessagePayload
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.RelayMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Zero-operation public LAN ingress — same contract as Android [HttpGatewayBridgeClient.pushPublic]
 * and PC Gateway `POST /api/public/sync/messages`.
 */
class GatewayPublicClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "payloadType"
    },
) {
    suspend fun pushPublic(
        gateway: DiscoveredGateway,
        bridgeId: String,
        bridgeName: String,
        messages: List<RelayMessage>,
    ): SyncMessagesResponse {
        val request = SyncMessagesRequest(
            bridgeId = bridgeId,
            bridgeName = bridgeName,
            messages = messages.map { it.toGatewayMessage(json) },
        )
        val response: HttpResponse = httpClient.post(
            "http://${gateway.host}:${gateway.port}/api/public/sync/messages",
        ) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SyncMessagesRequest.serializer(), request))
        }
        val text: String = response.body()
        return json.decodeFromString(SyncMessagesResponse.serializer(), text)
    }

    fun mapReceipts(response: SyncMessagesResponse): List<DeliveryReceipt> =
        response.receipts.mapNotNull { r ->
            val type = when (r.receiptType) {
                VERIFIED_GATEWAY_RECEIPT_TYPE -> ReceiptType.GATEWAY_RECEIVED
                UNVERIFIED_GATEWAY_RECEIPT_TYPE -> ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
                else -> return@mapNotNull null
            }
            DeliveryReceipt(r.receiptId, r.messageId, type, r.actorId, r.recordedAt)
        }
}

expect fun defaultHttpClient(): HttpClient

private fun RelayMessage.toGatewayMessage(json: Json): GatewayMessage = GatewayMessage(
    messageId = messageId,
    messageType = messageType.name,
    recordType = recordType.name,
    priority = priority.name,
    status = "ACTIVE",
    createdAt = createdAt,
    expiresAt = expiresAt,
    lifetimeMs = lifetimeMs,
    accumulatedAgeMs = accumulatedAgeMs,
    hopCount = hopCount,
    hopLimit = maxHopCount,
    originDeviceId = originDeviceId,
    payload = json.encodeToJsonElement(MessagePayload.serializer(), payload),
    receivedAt = receivedAt,
    reportSignature = reportSignature?.let { json.encodeToJsonElement(com.example.relay.rescue.ReportSignature.serializer(), it) },
)

fun createJsonHttpClient(engineClient: HttpClient): HttpClient = engineClient.config {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                classDiscriminator = "payloadType"
            },
        )
    }
}
