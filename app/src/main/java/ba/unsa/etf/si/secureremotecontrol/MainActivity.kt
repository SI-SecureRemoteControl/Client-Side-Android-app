/*package ba.unsa.etf.si.secureremotecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.ui.screens.RegisterScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import androidx.navigation.NavController

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainApp()
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "register") {
        composable("register") {
            RegisterScreen(navController)
        }
        composable("deregister") {
            DeregistrationScreen(navController)
        }
        composable("main") {
            MainScreen(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Secure Remote Control",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { navController.navigate("deregister") }) {
            Text("Go to Deregistration")
        }
    }
}*/

package ba.unsa.etf.si.secureremotecontrol

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ba.unsa.etf.si.secureremotecontrol.ui.screens.RegisterScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import ba.unsa.etf.si.secureremotecontrol.presentation.verification.DeregistrationScreen
import androidx.navigation.NavHostController

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            navController = rememberNavController()
            MainApp(navController)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_deregister -> {
                navController.navigate("deregister")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

@Composable
fun MainApp(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "register") {
        composable("register") {
            RegisterScreen(navController)
        }
        composable("deregister") {
            DeregistrationScreen(navController)
        }
        composable("main") {
            MainScreen(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavHostController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Secure Remote Control",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { navController.navigate("deregister") }) {
            Text("Go to Deregistration")
        }
    }
}