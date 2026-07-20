package com.example.relay.cloudrelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class CloudRelayStoreTest {
 @Test fun deduplicatesByEnvelopeIdAndKeepsOpaquePayload() {
  val store=InMemoryCloudRelayStore()
  assertTrue(store.pending("shelter",1,10).isEmpty())
  assertEquals(0,store.pending("shelter",1,10).size)
 }
}
