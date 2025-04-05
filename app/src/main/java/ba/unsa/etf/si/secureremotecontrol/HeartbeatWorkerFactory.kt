package ba.unsa.etf.si.secureremotecontrol

import android.content.Context
import androidx.work.WorkerParameters
import dagger.assisted.AssistedFactory

@AssistedFactory
interface HeartbeatWorkerFactory {
    fun create(appContext: Context, workerParams: WorkerParameters): HeartbeatWorker
}
