package com.example.relay.rescue
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.relay.RelayApplication
class CloudRescueRetryWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params) {
 override suspend fun doWork():Result {
  val app=applicationContext as? RelayApplication ?: return Result.failure()
  val key=RescueRequestKey(inputData.getString("request_id") ?: return Result.failure(),inputData.getInt("request_version",-1))
  val record=app.rescueRepository.get(key) ?: return Result.success()
  return when(CloudRescueDeliveryTransport(applicationContext,app.cloudRelayEndpoint).deliver(record.envelope)) {
   is RescueTransportResult.Stored -> Result.success()
   RescueTransportResult.Offline -> Result.retry()
   is RescueTransportResult.Failed -> Result.retry()
  }
 }
}
