// In SessionViewModel.kt

package ba.unsa.etf.si.secureremotecontrol.presentation.session
import android.app.Application
import androidx.lifecycle.AndroidViewModel
//import ba.unsa.etf.si.secureremotecontrol.util.JsonLogger
import ba.unsa.etf.si.secureremotecontrol.data.util.JsonLogger
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    fun onSessionStarted() {
        JsonLogger.log(getApplication(), "INFO", "SessionViewModel", "Session started")
    }
}