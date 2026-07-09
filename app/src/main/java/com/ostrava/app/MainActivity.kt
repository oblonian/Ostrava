package com.ostrava.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.MainViewModel
import com.ostrava.app.ui.detail.ActivityDetailScreen
import com.ostrava.app.ui.feed.FeedScreen
import com.ostrava.app.ui.onboarding.OnboardingScreen
import com.ostrava.app.ui.profile.ProfileScreen
import com.ostrava.app.ui.record.RecordScreen
import com.ostrava.app.ui.stats.StatsScreen
import com.ostrava.app.ui.theme.OstravaTheme

object Routes {
    const val FEED = "feed"
    const val RECORD = "record"
    const val STATS = "stats"
    const val PROFILE = "profile"
    const val ACTIVITY_DETAIL = "activity/{activityId}"

    fun activityDetail(id: Long) = "activity/$id"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.FEED, "Feed", Icons.AutoMirrored.Filled.List),
    BottomNavItem(Routes.RECORD, "Record", Icons.Filled.RadioButtonChecked),
    BottomNavItem(Routes.STATS, "Stats", Icons.Filled.BarChart),
    BottomNavItem(Routes.PROFILE, "Profile", Icons.Filled.Person),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OstravaTheme {
                OstravaAppUi()
            }
        }
    }
}

@Composable
private fun OstravaAppUi() {
    val mainViewModel: MainViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()

    when {
        settings == null -> {
            // Settings still loading; render nothing to avoid an onboarding flash.
        }
        settings?.onboardingDone == false -> {
            OnboardingScreen(onDone = { mainViewModel.completeOnboarding() })
        }
        else -> MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        OstravaNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun OstravaNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.FEED,
        modifier = modifier,
    ) {
        composable(Routes.FEED) {
            FeedScreen(
                onActivityClick = { id -> navController.navigate(Routes.activityDetail(id)) },
                onRecordClick = { navController.navigate(Routes.RECORD) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onActivitySaved = { id ->
                    navController.navigate(Routes.activityDetail(id)) {
                        popUpTo(Routes.FEED)
                    }
                },
            )
        }
        composable(Routes.STATS) {
            StatsScreen()
        }
        composable(Routes.PROFILE) {
            ProfileScreen()
        }
        composable(
            route = Routes.ACTIVITY_DETAIL,
            arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
        ) {
            ActivityDetailScreen(onDeleted = { navController.popBackStack() })
        }
    }
}
