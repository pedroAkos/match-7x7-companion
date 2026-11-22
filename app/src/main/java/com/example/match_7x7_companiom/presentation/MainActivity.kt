package com.example.match_7x7_companiom.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.match_7x7_companiom.presentation.theme.Match_7x7_companiomTheme

class MainActivity : ComponentActivity() {
    private val vm: MatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp(vm) }
    }
}

private const val HOME = "home"
private const val TEAM = "team"
private const val SCORER = "scorer"
private const val ASSIST = "assist"
private const val CONFIRM = "confirm"
private const val LOG = "log"
private const val EDIT = "edit/{id}"
private const val MATCHES = "matches"
private const val DETAIL = "detail/{id}"
private const val SYNC = "sync"

/** Ignores a second tap while the screen is already leaving (double taps). */
private fun NavController.go(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route)
}

@Composable
fun WearApp(vm: MatchViewModel) {
    Match_7x7_companiomTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            // Arriving back home (swipe, back, timeout) while a goal is in progress saves a
            // partial event. Arriving back at an edit screen cancels the edit.
            LaunchedEffect(nav) {
                nav.currentBackStackEntryFlow.collect { entry ->
                    val route = entry.destination.route
                    if (route == HOME || route == EDIT) vm.abandonPending()
                }
            }
            // Where an unfinished flow returns to: Home for a new goal, the edit screen for an edit.
            val leaveFlow = {
                nav.popBackStack(if (vm.pending?.editing == true) EDIT else HOME, inclusive = false)
                Unit
            }
            val goHome = { nav.popBackStack(HOME, inclusive = false); Unit }
            SwipeDismissableNavHost(navController = nav, startDestination = HOME) {
                composable(HOME) {
                    HomeScreen(
                        vm,
                        onGoal = { if (vm.startGoal(GoalType.GOAL)) nav.go(TEAM) },
                        onOwnGoal = { if (vm.startGoal(GoalType.OWN_GOAL)) nav.go(TEAM) },
                        onLog = { nav.go(LOG) },
                        onLoadMatch = { nav.go(MATCHES) },
                        onSync = { nav.go(SYNC) },
                    )
                }
                composable(TEAM) {
                    TeamScreen(vm, onPick = { t -> vm.pickTeam(t); nav.go(SCORER) }, onTimeout = leaveFlow)
                }
                composable(SCORER) {
                    val team = vm.pending?.team
                    if (team != null) PlayerPickerScreen(
                        vm,
                        title = if (vm.pending?.type == GoalType.OWN_GOAL) "Own goal" else "Scorer",
                        team = team,
                        exclude = null,
                        showNone = false,
                        onPick = { p ->
                            vm.pickScorer(p!!)
                            // An own goal has no assist.
                            nav.go(if (vm.pending?.type == GoalType.OWN_GOAL) CONFIRM else ASSIST)
                        },
                        onTimeout = leaveFlow,
                    )
                }
                composable(ASSIST) {
                    val team = vm.pending?.team
                    if (team != null) PlayerPickerScreen(
                        vm,
                        title = "Assist",
                        team = team,
                        exclude = vm.pending?.scorer,
                        showNone = true,
                        onPick = { p -> vm.pickAssist(p); nav.go(CONFIRM) },
                        onTimeout = leaveFlow,
                    )
                }
                composable(CONFIRM) {
                    ConfirmScreen(
                        vm,
                        onConfirm = {
                            val editing = vm.pending?.editing == true
                            vm.complete()
                            if (editing) nav.popBackStack(EDIT, inclusive = false) else goHome()
                        },
                        onTimeout = leaveFlow,
                    )
                }
                composable(MATCHES) {
                    MatchesScreen(vm, onPick = { s -> nav.go("detail/${s.id}") })
                }
                composable(DETAIL) { entry ->
                    val id = entry.arguments?.getString("id")
                    val summary = (vm.matchList as? Remote.Done)?.value?.firstOrNull { it.id == id }
                    if (summary != null) MatchDetailScreen(vm, summary, onLoaded = goHome)
                }
                composable(SYNC) { SyncScreen(vm, onClosed = goHome) }
                composable(LOG) {
                    LogScreen(vm, onEdit = { e -> nav.go("edit/${e.eventId}") })
                }
                composable(EDIT) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    EditScreen(
                        vm, id,
                        onChangePlayers = { if (vm.startEdit(id)) nav.go(TEAM) },
                        onDeleted = { nav.popBackStack(LOG, inclusive = false) },
                    )
                }
            }
        }
    }
}
