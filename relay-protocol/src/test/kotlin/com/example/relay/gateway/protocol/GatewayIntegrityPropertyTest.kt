package com.example.relay.gateway.protocol

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Property-based tests for the Gateway signing protocol. These verify structural
 * invariants (no false green possible from a single hand-picked example):
 * sign/verify round-trip, tamper detection, canonical-form field-boundary safety,
 * key-order independence, and crash-freedom on malformed signature input.
 */
@OptIn(ExperimentalKotest::class)
class GatewayIntegrityPropertyTest {

    private val keyPairs: List<Pair<String, KeyPair>> = (1..4).map { index ->
        "test-key-$index" to KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    private val verifier = EcdsaP256GatewayMessageVerifier { keyId ->
        keyPairs.firstOrNull { it.first == keyId }?.second?.public
    }

    private val arbPayload = Arb.bind(
        Arb.string(0..24),
        Arb.long(),
    ) { text, number ->
        JsonObject(mapOf("text" to JsonPrimitive(text), "number" to JsonPrimitive(number)))
    }

    private val arbMessage = arbitrary { rs ->
        GatewayMessage(
            messageId = Arb.string(1..64).bind(),
            messageType = Arb.string(0..32).bind(),
            recordType = Arb.string(0..32).bind(),
            priority = Arb.element("HIGH", "NORMAL", "LOW").bind(),
            status = Arb.string(0..16).bind(),
            createdAt = Arb.long().bind(),
            expiresAt = Arb.long().bind(),
            lifetimeMs = Arb.long().bind(),
            accumulatedAgeMs = Arb.long().bind(),
            hopCount = Arb.int().bind(),
            hopLimit = Arb.int().bind(),
            originDeviceId = Arb.string(0..48).bind(),
            payload = arbPayload.bind(),
            receivedAt = Arb.long().bind(),
        )
    }

    @Test
    fun signedMessageAlwaysVerifies(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 100), arbMessage, Arb.element(keyPairs)) { message, (keyId, pair) ->
            val signed = message.signWith(EcdsaP256GatewayMessageSigner(keyId, pair.private))
            assertEquals(GatewayVerificationResult.VERIFIED, signed.verifyWith(verifier))
        }
    }

    @Test
    fun tamperingAnyFieldInvalidatesSignature(): Unit = runBlocking {
        checkAll(
            PropTestConfig(iterations = 100),
            arbMessage,
            Arb.element(keyPairs),
            Arb.int(0..4),
        ) { message, (keyId, pair), fieldIndex ->
            val signed = message.signWith(EcdsaP256GatewayMessageSigner(keyId, pair.private))
            val tampered = when (fieldIndex) {
                0 -> signed.copy(messageId = signed.messageId + "x")
                1 -> signed.copy(hopCount = signed.hopCount + 1)
                2 -> signed.copy(priority = signed.priority + "!")
                3 -> signed.copy(payload = JsonPrimitive("tampered"))
                else -> signed.copy(receivedAt = signed.receivedAt + 1)
            }
            assertNotEquals(GatewayVerificationResult.VERIFIED, tampered.verifyWith(verifier))
        }
    }

    @Test
    fun signingWithUnknownKeyNeverVerifies(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 50), arbMessage) { message ->
            val rogue = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val signed = message.signWith(EcdsaP256GatewayMessageSigner("unregistered-key", rogue.private))
            assertEquals(GatewayVerificationResult.UNKNOWN_KEY, signed.verifyWith(verifier))
        }
    }

    @Test
    fun malformedSignatureStringsNeverCrashAndNeverVerify(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 500), arbMessage, Arb.string(0..128)) { message, junk ->
            val forged = message.copy(
                integrity = GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, keyPairs.first().first, junk),
            )
            // Must classify, never throw and never verify.
            assertNotEquals(GatewayVerificationResult.VERIFIED, forged.verifyWith(verifier))
        }
    }

    @Test
    fun canonicalFormHasNoFieldBoundaryCollisions(): Unit = runBlocking {
        // If concatenated adjacent fields are equal but the split differs, the
        // length-prefixed canonical form must still differ.
        val arbSplit = Arb.string(2..32).map { combined ->
            val mid = combined.length / 2
            Pair(
                combined.substring(0, mid) to combined.substring(mid),
                combined.substring(0, 1) to combined.substring(1),
            )
        }
        checkAll(PropTestConfig(iterations = 300), arbMessage, arbSplit) { message, (splitA, splitB) ->
            if (splitA != splitB) {
                val a = message.copy(messageId = splitA.first, messageType = splitA.second)
                val b = message.copy(messageId = splitB.first, messageType = splitB.second)
                assertFalse(a.canonicalBytes().contentEquals(b.canonicalBytes()))
            }
        }
    }

    @Test
    fun canonicalBytesIgnoreJsonKeyInsertionOrder(): Unit = runBlocking {
        checkAll(
            PropTestConfig(iterations = 200),
            arbMessage,
            Arb.string(1..8),
            Arb.string(9..16),
        ) { message, keyA, keyB ->
            val forward = JsonObject(linkedMapOf(keyA to JsonPrimitive(1L), keyB to JsonPrimitive(2L)))
            val backward = JsonObject(linkedMapOf(keyB to JsonPrimitive(2L), keyA to JsonPrimitive(1L)))
            assertArrayEquals(
                message.copy(payload = forward).canonicalBytes(),
                message.copy(payload = backward).canonicalBytes(),
            )
        }
    }
}
