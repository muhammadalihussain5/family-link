package com.hashmi.familylink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.hashmi.familylink.data.AppMode
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.ui.ClientMainScreen
import com.hashmi.familylink.ui.NavKey
import com.hashmi.familylink.ui.PermissionScreen
import com.hashmi.familylink.ui.ServerMainScreen
import com.hashmi.familylink.ui.SettingsScreen
import com.hashmi.familylink.ui.SetupWizardScreen
import com.hashmi.familylink.ui.theme.FamilyLinkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userPreferencesRepository = UserPreferencesRepository(this)
        enableEdgeToEdge()
        setContent {
            val appMode by userPreferencesRepository.appModeFlow
                .collectAsStateWithLifecycle(initialValue = null)

            FamilyLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val mode = appMode
                    when (mode) {
                        null -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        AppMode.UNDEFINED -> {
                            SetupWizardScreen(
                                onModeSelected = { mode ->
                                    lifecycleScope.launch {
                                        userPreferencesRepository.ensureIdentity()
                                        userPreferencesRepository.updateAppMode(mode)
                                    }
                                }
                            )
                        }
                        else -> MainNavigation(mode)
                    }
                }
            }
        }
    }
}

@Composable
fun MainNavigation(initialMode: AppMode) {
    val start = if (initialMode == AppMode.SERVER) NavKey.ServerMain else NavKey.ClientMain
    val backStack = rememberNavBackStack(start)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() }
    ) { key ->
        when (key) {
            is NavKey.ServerMain -> NavEntry(key) {
                ServerMainScreen(
                    onNavigateToSettings = { backStack.add(NavKey.Settings) }
                )
            }
            is NavKey.ClientMain -> NavEntry(key) {
                ClientMainScreen(
                    onNavigateToPermissions = { backStack.add(NavKey.ClientPermissions) },
                    onNavigateToSettings = { backStack.add(NavKey.Settings) }
                )
            }
            is NavKey.ClientPermissions -> NavEntry(key) {
                PermissionScreen(
                    onAllPermissionsGranted = { backStack.removeLastOrNull() }
                )
            }
            is NavKey.Settings -> NavEntry(key) {
                SettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
            is NavKey.SetupWizard -> NavEntry(key) {
                SetupWizardScreen(onModeSelected = {})
            }
            else -> error("Unknown key: $key")
        }
    }
}
