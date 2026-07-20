package com.example.relay.rescue
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
@Serializable
data class CloudStoredReceipt(val receiptId:String,val envelopeId:String,val requestId:String,val requestVersion:Int,val ciphertextSha256Hex:String,val shelterId:String,val storedAtEpochMillis:Long,val status:String="CLOUD_STORED")
class CloudRescueDeliveryTransport(context:Context,private val endpoint:String,private val json:Json=Json { ignoreUnknownKeys=false; encodeDefaults=true }):RescueDeliveryTransport {
 private val connectivity=context.getSystemService(ConnectivityManager::class.java)
 init { require(endpoint.startsWith("https://")) { "Cloud Relay requires HTTPS" } }
 override suspend fun deliver(envelope:EncryptedRescueEnvelope):RescueTransportResult {
  val n=connectivity.activeNetwork; val caps=n?.let(connectivity::getNetworkCapabilities)
  if(caps==null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return RescueTransportResult.Offline
  return runCatching {
   val c=(URL(endpoint+"/v1/rescue/envelopes").openConnection() as HttpURLConnection).apply { requestMethod="POST";doOutput=true;connectTimeout=10000;readTimeout=15000;setRequestProperty("Content-Type","application/json") }
   c.outputStream.use { it.write(json.encodeToString(envelope).toByteArray()) }
   if(c.responseCode !in 200..299) return RescueTransportResult.Failed("HTTP_"+c.responseCode)
   RescueTransportResult.Stored(json.decodeFromString(c.inputStream.bufferedReader().readText()))
  }.getOrElse { RescueTransportResult.Failed("NETWORK_FAILURE") }
 }
}
