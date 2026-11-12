package com.ostrava.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ostrava.app.data.db.RecordingDraftEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.InsightAction
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.notify.Notifications
import com.ostrava.app.tracking.TrackingService
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.MainViewModel
import com.ostrava.app.ui.NavRequest
import com.ostrava.app.ui.NavRequests
import com.ostrava.app.ui.detail.ActivityDetailScreen
import com.ostrava.app.ui.feed.FeedScreen
import com.ostrava.app.ui.onboarding.OnboardingScreen
import com.ostrava.app.ui.profile.ProfileScreen
import com.ostrava.app.ui.record.RecordScreen
import com.ostrava.app.ui.share.ShareScreen
import com.ostrava.app.ui.stats.StatsScreen
import com.ostrava.app.ui.theme.HeroGradient
import com.ostrava.app.ui.theme.OstravaTheme

object Routes {
    const val FEED = "feed"
    const val RECORD = "record"
    const val STATS = "stats"
    const val PROFILE = "profile"
    const val ACTIVITY_DETAIL = "activity/{activityId}"
    const val SHARE = "share/{activityId}"

    fun activityDetail(id: Long) = "activity/$id"

    fun share(id: Long) = "share/$id"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.FEED, "Home", Icons.Filled.Home),
    BottomNavItem(Routes.RECORD, "Record", Icons.Filled.PlayArrow),
    BottomNavItem(Routes.STATS, "Stats", Icons.Filled.BarChart),
    BottomNavItem(Routes.PROFILE, "You", Icons.Filled.Person),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            OstravaTheme {
                OstravaAppUi()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Notifications carry where to go; hand that to the navigation layer. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val activityId = intent.getLongExtra(Notifications.EXTRA_OPEN_ACTIVITY_ID, -1L)
        val destination = intent.getStringExtra(Notifications.EXTRA_OPEN_DESTINATION)
        when {
            activityId > 0 -> NavRequests.pending.value = NavRequest.OpenActivity(activityId)
            destination != null -> NavRequests.pending.value = NavRequest.OpenDestination(destination)
        }
    }
}

@Composable
private fun OstravaAppUi() {
    val mainViewModel: MainViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()

    when {
        settings == null -> {
            // Settings still loading; show the background rather than flashing onboarding.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        settings?.onboardingDone == false -> {
            OnboardingScreen(onDone = { mainViewModel.completeOnboarding() })
        }
        else -> MainScaffold(mainViewModel)
    }
}

@Composable
private fun MainScaffold(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val context = LocalContext.current

    fun openTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Requests from outside the UI: a tapped notification, for example.
    val navRequest by NavRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(navRequest) {
        when (val request = navRequest) {
            is NavRequest.OpenActivity -> navController.navigate(Routes.activityDetail(request.id))
            is NavRequest.OpenDestination -> when (request.route) {
                Notifications.DESTINATION_RECORD -> openTab(Routes.RECORD)
                Notifications.DESTINATION_STATS -> openTab(Routes.STATS)
                Notifications.DESTINATION_PROFILE -> openTab(Routes.PROFILE)
            }
            null -> return@LaunchedEffect
        }
        NavRequests.pending.value = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // The share screen has its own action bar and wants the whole height.
            if (currentDestination?.route != Routes.SHARE) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        val isRecord = item.route == Routes.RECORD
                        NavigationBarItem(
                            selected = selected,
                            onClick = { openTab(item.route) },
                            icon = {
                                if (isRecord) {
                                    // Recording is what the app is for, so its tab stands out.
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(HeroGradient, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(item.icon, contentDescription = null, tint = Color.White)
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = null)
                                }
                            },
                            label = {
                                Text(item.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = if (isRecord) Color.Transparent
                                else MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        OstravaNavHost(
            navController = navController,
            openTab = ::openTab,
            modifier = Modifier.padding(innerPadding),
        )
    }

    val interrupted by mainViewModel.interruptedRecording.collectAsStateWithLifecycle()
    interrupted?.let { draft ->
        InterruptedRecordingDialog(
            draft = draft,
            onResume = {
                TrackingService.resumeDraft(context)
                openTab(Routes.RECORD)
            },
            onSave = {
                mainViewModel.saveInterruptedRecording { id ->
                    navController.navigate(Routes.activityDetail(id))
                }
            },
            onDiscard = { mainViewModel.discardInterruptedRecording() },
        )
    }
}

/** Offered when a recording was cut short by a crash or by the phone closing the app. */
@Composable
private fun InterruptedRecordingDialog(
    draft: RecordingDraftEntity,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val type = ActivityType.fromName(draft.type)
    val progress = buildString {
        append(type.label.lowercase())
        if (draft.distanceMeters > 0) {
            append(" (${formatDistance(draft.distanceMeters, false)}, ${formatDuration(draft.movingTimeMillis)})")
        }
    }
    AlertDialog(
        // Deliberately not dismissable by tapping outside: the recording needs a decision.
        onDismissRequest = {},
        title = { Text("Your recording was interrupted") },
        text = {
            Text(
                "The app closed while you were recording a $progress. Nothing was lost — " +
                    "you can carry on recording, save what's there, or throw it away."
            )
        },
        confirmButton = { TextButton(onClick = onResume) { Text("Carry on", fontWeight = FontWeight.Bold) } },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onSave) { Text("Save it") }
        },
    )
}

@Composable
private fun OstravaNavHost(
    navController: NavHostController,
    openTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.FEED,
        modifier = modifier,
    ) {
        composable(Routes.FEED) {
            FeedScreen(
                onActivityClick = { id -> navController.navigate(Routes.activityDetail(id)) },
                onRecordClick = { openTab(Routes.RECORD) },
                onInsightAction = { action ->
                    when (action) {
                        InsightAction.RECORD -> openTab(Routes.RECORD)
                        InsightAction.STATS -> openTab(Routes.STATS)
                        InsightAction.BACKUP -> openTab(Routes.PROFILE)
                        InsightAction.NONE -> Unit
                    }
                },
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
            StatsScreen(
                onActivityClick = { id -> navController.navigate(Routes.activityDetail(id)) },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen()
        }
        composable(
            route = Routes.ACTIVITY_DETAIL,
            arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
        ) {
            ActivityDetailScreen(
                onDeleted = { navController.popBackStack() },
                onShare = { id -> navController.navigate(Routes.share(id)) },
            )
        }
        composable(
            route = Routes.SHARE,
            arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
        ) {
            ShareScreen(onBack = { navController.popBackStack() })
        }
    }
}
