package ba.unsa.etf.si.secureremotecontrol

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
//import androidx.work.Worker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

//background task that sends a heartbeat signal from the device every 10s,
// used to signal that the device is active-online

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val webRTCClient: WebRTCClient
) : CoroutineWorker(appContext, workerParams)
 {

    override suspend fun doWork(): Result {
        Log.d("HeartbeatWorker", "Heartbeat worker triggered at: ${System.currentTimeMillis()}")
        return try {
            // retireve deviceId from input data
            val deviceId = inputData.getString("deviceId") ?: return Result.failure()
            webRTCClient.sendHeartbeat(deviceId)

            // Schedule next heartbeat in 10 seconds, passing deviceId to the next work
            val nextWork = OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setInputData(workDataOf("deviceId" to deviceId))  // Pass deviceId to next worker
                .build()

            WorkManager.getInstance(applicationContext)
                .enqueue(nextWork)

            Log.d("HeartbeatWorker", "Next heartbeat scheduled.")  // Log here
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("HeartbeatWorker", "Error: ${e.message}")  // Log error
            Result.retry()
        }
    }

}
