package au.buzz.ryzewave.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import au.buzz.ryzewave.R

/** The four bottom-bar destinations. */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Home", Icons.Filled.Dashboard),
    HISTORY("history", "History", Icons.Filled.Timeline),
    WORKOUT("workout", "Workout", Icons.AutoMirrored.Filled.DirectionsRun),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

const val WORKOUT_DETAIL_ROUTE = "workoutDetail/{id}"
fun workoutDetailRoute(id: Long): String = "workoutDetail/$id"

/**
 * Root composable: Material3 theme (dynamic colour when available), bottom navigation and the NavHost.
 * [healthHost] is provided by MainActivity (it owns the Health Connect permission launcher);
 * [showHealthRationale] opens the permission-rationale dialog Health Connect asks apps to show.
 */
@Composable
fun RyzeApp(
    healthHost: HealthPermissionHost = NoHealthPermissionHost,
    showHealthRationale: Boolean = false,
    onRationaleDismissed: () -> Unit = {},
) {
    RyzeTheme {
        CompositionLocalProvider(LocalHealthPermissionHost provides healthHost) {
            val nav = rememberNavController()
            Scaffold(bottomBar = { RyzeBottomBar(nav) }) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = Tab.DASHBOARD.route,
                    // consume the root insets so the screens' own Scaffolds do not pad for the status bar again
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                ) {
                    composable(Tab.DASHBOARD.route) { DashboardScreen() }
                    composable(Tab.HISTORY.route) { HistoryScreen() }
                    composable(Tab.WORKOUT.route) {
                        WorkoutScreen(onOpenWorkout = { id -> nav.navigate(workoutDetailRoute(id)) { launchSingleTop = true } })
                    }
                    composable(
                        WORKOUT_DETAIL_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        WorkoutDetailScreen(id = id, onBack = { nav.popBackStack() })
                    }
                    composable(Tab.SETTINGS.route) { SettingsScreen() }
                }
            }
            if (showHealthRationale) HealthRationaleDialog(onDismiss = onRationaleDismissed)
        }
    }
}

@Composable
private fun RyzeBottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.route == tab.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
private fun HealthRationaleDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Health Connect") },
        text = { Text(stringResource(R.string.health_permission_rationale)) },
    )
}
