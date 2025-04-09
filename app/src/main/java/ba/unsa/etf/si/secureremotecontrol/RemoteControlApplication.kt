package ba.unsa.etf.si.secureremotecontrol


import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import ba.unsa.etf.si.secureremotecontrol.lifecycle.AppLifecycleObserver // Import your observer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp // *** THIS IS CRITICAL FOR HILT ***
class RemoteControlApplication : Application() {

    // Ask Hilt to provide the instance of AppLifecycleObserver
    @Inject
    lateinit var appLifecycleObserver: AppLifecycleObserver

    override fun onCreate() {
        super.onCreate()
        Log.d("MyApp", "Application onCreate started.")

        // Register the observer with the application's lifecycle
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        Log.d("MyApp", "AppLifecycleObserver registered.")
    }
}