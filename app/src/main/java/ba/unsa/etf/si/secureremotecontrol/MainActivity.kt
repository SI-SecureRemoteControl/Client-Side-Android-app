package ba.unsa.etf.si.secureremotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.data.datastore.TokenDataStore
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ba.unsa.etf.si.secureremotecontrol.presentation.main.MainViewModel
import javax.inject.Inject
import androidx.hilt.navigation.compose.hiltViewModel



@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var tokenDataStore: TokenDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecureRemoteControlTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = determineStartDestination()
                ) {
                    composable("registration") {
                        RegisterScreen(
                            onRegistrationSuccess = {
                                navController.navigate("main") {
                                    popUpTo("registration") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("main") {
                        MainScreen(
                            onDeregister = {
                                navController.navigate("deregister")
                            }
                        )
                    }

                    composable("deregister") {
                        DeregistrationScreen(
                            navController = navController,
                            onDeregisterSuccess = {
                                navController.navigate("registration") {
                                    popUpTo("deregister") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun determineStartDestination(): String {
        return runBlocking {
            val token = tokenDataStore.token.first()
            if (token.isNullOrEmpty()) "registration" else "main"
        }
    }
}

