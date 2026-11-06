package com.handy.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.handy.android.graph
import com.handy.android.ui.theme.HandyTheme
import kotlinx.coroutines.launch

enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    MODELS("models", "Models", Icons.Outlined.Memory, Icons.Filled.Memory),
    HISTORY("history", "History", Icons.Outlined.History, Icons.Filled.History),
    SETTINGS("settings", "Settings", Icons.Outlined.Tune, Icons.Filled.Tune),
}

class MainActivity : ComponentActivity() {

    private val tab = mutableStateOf(Tab.HOME)
    /** Bumped on resume / focus so status cards re-check keyboard + permission state. */
    val refreshTick = mutableStateOf(0)

    val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick.value++ }
    val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { graph.files.transcribe(it); tab.value = Tab.HOME }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("tab")?.let { n -> Tab.entries.firstOrNull { it.name == n }?.let { tab.value = it } }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()
            HandyTheme(settings.theme) {
                AnimatedContent(settings.onboardingCompleted, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { done ->
                    if (!done) OnboardingScreen(this@MainActivity) else MainScaffold()
                }
            }
        }
    }

    @Composable
    private fun MainScaffold() {
        var current by tab
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = current == t,
                            onClick = { current = t },
                            icon = { Icon(if (current == t) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            val mod = Modifier.padding(padding)
            when (current) {
                Tab.HOME -> HomeScreen(this, mod, onOpenModels = { current = Tab.MODELS })
                Tab.MODELS -> ModelsScreen(this, mod)
                Tab.HISTORY -> HistoryScreen(this, mod)
                Tab.SETTINGS -> SettingsScreen(this, mod, onOpenModels = { current = Tab.MODELS })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshTick.value++
    }

    override fun onPause() {
        super.onPause()
        graph.engine.scheduleUnload()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The IME picker is a dialog over us; focus returns when it closes.
        if (hasFocus) refreshTick.value++
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.route == name }?.let { tab.value = it } }
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            lifecycleScope.launch { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
        val audioUri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (audioUri != null) {
            graph.files.transcribe(audioUri)
            tab.value = Tab.HOME
            // Sharing shouldn't force the setup flow on someone who just wants a transcript.
            if (!graph.settings.current.onboardingCompleted && graph.models.installed().isNotEmpty()) {
                graph.settings.update { it.copy(onboardingCompleted = true) }
            }
        }
    }

    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}
