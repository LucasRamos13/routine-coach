package dev.routinecoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.routinecoach.app.data.AppSettings
import dev.routinecoach.app.data.PackState
import dev.routinecoach.app.ui.RoutineCoachTheme
import dev.routinecoach.app.ui.screens.AutomationHistoryScreen
import dev.routinecoach.app.ui.screens.CalendarScreen
import dev.routinecoach.app.ui.screens.DisclaimerScreen
import dev.routinecoach.app.ui.screens.ExerciseScreen
import dev.routinecoach.app.ui.screens.GuideScreen
import dev.routinecoach.app.ui.screens.LibraryScreen
import dev.routinecoach.app.ui.screens.PackErrorScreen
import dev.routinecoach.app.ui.screens.ProgressScreen
import dev.routinecoach.app.ui.screens.SessionDetailScreen
import dev.routinecoach.app.ui.screens.SessionScreen
import dev.routinecoach.app.ui.screens.SettingsScreen
import dev.routinecoach.app.ui.screens.StopScreen
import dev.routinecoach.app.ui.screens.TodayScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoute = intent.getStringExtra("route")
        setContent {
            val c = container
            val settings by c.settings.flow.collectAsState(initial = null)
            val pack by c.packs.state.collectAsState()
            val s = settings
            RoutineCoachTheme(fontScale = s?.fontScale ?: 1f, highContrast = s?.highContrast ?: false) {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        s == null || pack is PackState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        pack is PackState.Invalid -> PackErrorScreen((pack as PackState.Invalid).issues)
                        !s.disclaimerAccepted -> DisclaimerScreen()
                        else -> AppNav(s, startRoute)
                    }
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.FitnessCenter),
    Tab("calendar", "Calendar", Icons.Filled.CalendarMonth),
    Tab("progress", "Progress", Icons.Filled.Insights),
    Tab("library", "Exercises", Icons.Filled.MenuBook),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun AppNav(settings: AppSettings, startRoute: String?) {
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val current = back?.destination?.route
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(startRoute) {
        if (startRoute == "automation") nav.navigate("automation")
        if (startRoute == "settings") nav.navigate("settings")
    }
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == current }) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo("today") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(padding)) {
            composable("today") { TodayScreen(nav, settings) }
            composable("calendar") { CalendarScreen(nav) }
            composable("progress") { ProgressScreen(nav) }
            composable("library") { LibraryScreen(nav) }
            composable("settings") { SettingsScreen(nav, settings) }
            composable("automation") { AutomationHistoryScreen(nav) }
            composable("stop") { StopScreen(nav) }
            composable("guide") { GuideScreen(nav) }
            composable(
                "session?entry={entry}&session={session}&variant={variant}&resume={resume}",
                arguments = listOf(
                    navArgument("entry") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("variant") { type = NavType.StringType; defaultValue = "FULL" },
                    navArgument("resume") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { e ->
                SessionScreen(
                    nav,
                    entryId = e.arguments!!.getLong("entry").takeIf { it >= 0 },
                    sessionId = e.arguments!!.getString("session"),
                    variant = e.arguments!!.getString("variant") ?: "FULL",
                    resumeId = e.arguments!!.getLong("resume").takeIf { it >= 0 },
                )
            }
            composable("exercise/{id}") { e -> ExerciseScreen(nav, e.arguments!!.getString("id")!!) }
            composable("history/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                SessionDetailScreen(nav, e.arguments!!.getLong("id"))
            }
        }
    }
}

fun androidx.navigation.NavController.startSession(entryId: Long?, sessionId: String, variant: String) =
    navigate("session?entry=${entryId ?: -1}&session=$sessionId&variant=$variant")
