package com.example.relay.cloudrelay

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.validate
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.network.tls.certificates.*
import java.io.File
import java.security.KeyStore

fun main() {
    val file=File(System.getenv("RELAY_CLOUD_KEYSTORE") ?: error("RELAY_CLOUD_KEYSTORE is required"))
    val password=(System.getenv("RELAY_CLOUD_KEYSTORE_PASSWORD") ?: error("RELAY_CLOUD_KEYSTORE_PASSWORD is required")).toCharArray()
    val ks=KeyStore.getInstance("JKS").apply { load(file.inputStream(),password) }
    val alias=System.getenv("RELAY_CLOUD_KEY_ALIAS") ?: "relay-server"
    val url=System.getenv("RELAY_CLOUD_JDBC_URL") ?: error("RELAY_CLOUD_JDBC_URL is required")
    embeddedServer(Netty, applicationEngineEnvironment {
        sslConnector(ks,password) {
            this.keyAlias=alias
            this.port=System.getenv("RELAY_CLOUD_PORT")?.toIntOrNull() ?: 8443
            this.keyStorePath=file
            this.clientAuth=io.ktor.network.tls.certificates.ClientAuth.REQUIRED
        }
        module { cloudRelayModule(PostgresCloudRelayStore(url,System.getenv("RELAY_CLOUD_DB_USER") ?: "",System.getenv("RELAY_CLOUD_DB_PASSWORD") ?: "")) }
    }).start(wait=true)
}
fun Application.cloudRelayModule(store:CloudRelayStore) {
    install(ContentNegotiation) { json() }
    routing {
        post("/v1/rescue/envelopes") {
            val e=call.receive<EncryptedRescueEnvelope>(); val now=System.currentTimeMillis()
            if(e.validate()!=RescueValidationResult.Valid || !RescueCryptography.verifyEnvelopeFraming(e) || e.expiresAtEpochMillis<=now) { call.respond(HttpStatusCode.BadRequest); return@post }
            call.respond(EnvelopeUploadResponse(store.put(e,now)))
        }
        get("/v1/gateways/{shelterId}/envelopes") {
            val id=call.parameters["shelterId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(GatewayEnvelopeBatch(store.pending(id,System.currentTimeMillis(),(call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1,100))))
        }
        post("/v1/gateways/{shelterId}/receipts") { call.receive<GatewayReceiptRequest>(); call.respond(HttpStatusCode.Accepted) }
        get("/v1/devices/{deviceKeyId}/receipts") { call.respond(emptyList<CloudStoredReceipt>()) }
    }
}
