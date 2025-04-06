package ba.unsa.etf.si.secureremotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ba.unsa.etf.si.secureremotecontrol.presentation.home.HomeScreen
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.ui.screens.RegisterScreen

import ba.unsa.etf.si.secureremotecontrol.ui.theme.SecureRemoteControlTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecureRemoteControlTheme {

                Surface(color = MaterialTheme.colorScheme.background) {
                    MainApp()

                var showSplash by remember { mutableStateOf(true) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showSplash) {
                        SplashScreen(onTimeout = { showSplash = false })
                    } else {
                        RegisterScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController)
        }
        composable("verify") {
            DeregistrationScreen(navController)
        }
    }
}
