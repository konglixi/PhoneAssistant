package dev.phoneassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dev.phoneassistant.ui.AssistantViewModel
import dev.phoneassistant.ui.navigation.PhoneAssistantNavHost

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<AssistantViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    PhoneAssistantNavHost(
                        navController = navController,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}
