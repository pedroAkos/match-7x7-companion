package com.example.match_7x7_companiom.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.match_7x7_companiom.data.MatchSummary
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val ABANDON_AFTER_MS = 30_000L

/** Match time as mm:ss since kick-off; frozen once the match has ended. */
@Composable
fun matchTime(kickoff: Instant?, ended: Instant?): String {
    if (kickoff == null) return "00:00"
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(kickoff, ended) {
        while (ended == null) {
            now = Instant.now()
            delay(1000 - Duration.between(kickoff, now).toMillis() % 1000)
        }
    }
    val seconds = maxOf(0L, Duration.between(kickoff, ended ?: now).seconds)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** The small button under the Own | Log row; what it does depends on the match phase. */
@Composable
private fun SlotButton(label: String, confirmLabel: String?, onClick: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        if (confirm) { delay(3000); confirm = false }
    }
    FilledTonalButton(
        onClick = { if (confirmLabel == null || confirm) onClick() else confirm = true },
        modifier = Modifier.fillMaxWidth(0.8f).height(32.dp),
        colors = if (confirm) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer)
        else ButtonDefaults.filledTonalButtonColors(),
    ) {
        Text(if (confirm) confirmLabel!! else label,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun HomeScreen(
    vm: MatchViewModel,
    onGoal: () -> Unit, onOwnGoal: () -> Unit, onLog: () -> Unit,
    onLoadMatch: () -> Unit, onSync: () -> Unit,
) {
    val phase = vm.phase
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!vm.ready) return@Column
            if (phase == Phase.NO_MATCH) {
                Spacer(Modifier.weight(1f))
                Text("No match loaded", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onLoadMatch, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("LOAD MATCH", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                return@Column
            }
            val teamA = vm.teams[0]
            val teamB = vm.teams[1]
            // Before kick-off the clock would read 00:00, so show which match is loaded instead.
            if (phase == Phase.LOADED) {
                Text("${teamA.name} v ${teamB.name}", style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(matchTime(vm.kickoffAt, vm.endedAt), style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${vm.score(teamA.id)}", color = teamA.color.lighter(), style = MaterialTheme.typography.titleMedium)
                Text("–", style = MaterialTheme.typography.titleMedium)
                Text("${vm.score(teamB.id)}", color = teamB.color.lighter(), style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = if (phase == Phase.LOADED) vm::kickOff else onGoal,
                enabled = phase == Phase.LOADED || phase == Phase.RUNNING,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    when (phase) {
                        Phase.LOADED -> "KICK-OFF"; Phase.RUNNING -> "GOAL"
                        Phase.ENDED -> "ENDED"; else -> "SYNCED ✓"
                    },
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge, maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onOwnGoal, enabled = phase == Phase.RUNNING,
                    modifier = Modifier.weight(1f).height(34.dp)) { Text("Own", maxLines = 1) }
                FilledTonalButton(onClick = onLog, modifier = Modifier.weight(1f).height(34.dp)) {
                    Text("Log ${vm.events.size}", maxLines = 1)
                }
            }
            when (phase) {
                Phase.LOADED -> SlotButton("Unload", "Tap again to unload", vm::removeMatch)
                Phase.RUNNING -> SlotButton("End match", "Tap again to end", vm::endMatch)
                Phase.ENDED -> SlotButton("Sync", null, onSync)
                else -> SlotButton("Close match", null, onSync)
            }
        }
    }
}

private val dateTime = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

private fun MatchSummary.title() = teams.joinToString(" v ") { it.name }
private fun MatchSummary.result() =
    if (finished) "FT ${teams.joinToString("–") { "${score[it.id] ?: 0}" }} · $goalCount goals" else "Scheduled"

/** Matches on the server; only scheduled ones can be picked. */
@Composable
fun MatchesScreen(vm: MatchViewModel, onPick: (MatchSummary) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshList() }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(contentPadding = padding, state = listState) {
            header(spec) { Text("Matches") }
            when (val l = vm.matchList) {
                is Remote.Idle, is Remote.Loading -> item { Text("Loading…", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                is Remote.Failed -> {
                    item { Text(l.message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    item {
                        Button(onClick = vm::refreshList, modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec)) {
                            Text("Retry", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                    }
                }
                is Remote.Done -> for (m in l.value) item {
                    FilledTonalButton(
                        onClick = { onPick(m) },
                        enabled = !m.finished,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text("${dateTime.format(m.scheduledAt)}\n${m.title()}\n${m.result()}",
                            Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Confirm this is the right match, then fetch its roster onto the watch. */
@Composable
fun MatchDetailScreen(vm: MatchViewModel, match: MatchSummary, onLoaded: () -> Unit) {
    val state = vm.loadState
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(match.title(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(dateTime.format(match.scheduledAt))
            Text("${match.playerCount} players", style = MaterialTheme.typography.bodySmall)
            if (state is Remote.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.loadMatch(match.id, onLoaded) }, enabled = state !is Remote.Loading,
                modifier = Modifier.fillMaxWidth()) {
                Text(when (state) { is Remote.Loading -> "Loading…"; is Remote.Failed -> "Retry"; else -> "Load" },
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Uploads an ended match; once synced, shows what the server stored next to the watch's numbers. */
@Composable
fun SyncScreen(vm: MatchViewModel, onClosed: () -> Unit) {
    LaunchedEffect(Unit) { vm.sync() }
    val phase = vm.phase
    val state = vm.syncState
    val m = vm.match
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            if (phase == Phase.SYNCED && m != null && vm.teams.size == 2) {
                val a = vm.teams[0].id; val b = vm.teams[1].id
                val partials = vm.events.count { it.status == EventStatus.PARTIAL }
                val goals = vm.events.count { it.status == EventStatus.COMPLETE }
                val watch = "${vm.score(a)}–${vm.score(b)} · $goals goals" + if (partials > 0) " +$partials partial" else ""
                val server = "${m.serverScoreA}–${m.serverScoreB} · ${m.serverGoals} goals" +
                    if ((m.serverPartials ?: 0) > 0) " +${m.serverPartials} partial" else ""
                val same = watch == server
                Text(if (same) "Synced ✓" else "Synced, but differs!", style = MaterialTheme.typography.titleMedium,
                    color = if (same) Color.Unspecified else MaterialTheme.colorScheme.error)
                Text("Watch  $watch", style = MaterialTheme.typography.bodySmall)
                Text("Server $server", style = MaterialTheme.typography.bodySmall)
                SlotButton("Close match", "Tap again to close") { vm.removeMatch(); onClosed() }
            } else when (state) {
                is Remote.Loading -> Text("Syncing…", style = MaterialTheme.typography.titleMedium)
                is Remote.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = vm::sync, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                else -> if (phase == Phase.ENDED) {
                    Text("Changed while syncing", textAlign = TextAlign.Center)
                    Button(onClick = vm::sync, modifier = Modifier.fillMaxWidth()) {
                        Text("Sync again", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Step 2 of the flow: which team (own goal: the team of the player who put it in). */
@Composable
fun TeamScreen(vm: MatchViewModel, onPick: (Team) -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(ABANDON_AFTER_MS)
        onTimeout()
    }
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 34.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (vm.pending?.type == GoalType.OWN_GOAL) "Own goal by" else "Goal for")
            for (team in vm.teams) {
                Button(
                    onClick = { onPick(team) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = team.color, contentColor = Color.White),
                ) {
                    Text(team.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Scorer and assist pickers: one team's roster, optionally without one player and with a None row. */
@Composable
fun PlayerPickerScreen(
    vm: MatchViewModel,
    title: String,
    team: Team,
    exclude: Player?,
    showNone: Boolean,
    onPick: (Player?) -> Unit,
    onTimeout: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(ABANDON_AFTER_MS)
        onTimeout()
    }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(contentPadding = padding, state = listState) {
            header(spec) { Text("$title · ${team.name}", color = team.color.lighter()) }
            if (showNone) {
                item {
                    FilledTonalButton(
                        onClick = { onPick(null) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) { Text("None", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                }
            }
            for (p in vm.players.filter { it.teamId == team.id && it.id != exclude?.id }) {
                item {
                    Button(
                        onClick = { onPick(p) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = team.color, contentColor = Color.White),
                    ) { Text(p.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/** Last step: Confirm commits the goal; letting the timer run out saves a partial event. */
@Composable
fun ConfirmScreen(vm: MatchViewModel, onConfirm: () -> Unit, onTimeout: () -> Unit) {
    val p = vm.pending ?: return
    var left by remember { mutableIntStateOf((ABANDON_AFTER_MS / 1000).toInt()) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000)
            left--
        }
        onTimeout()
    }
    val team = p.scorer?.let { s -> vm.teams.firstOrNull { it.id == s.teamId } }
    ScreenScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 34.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            if (p.type == GoalType.OWN_GOAL) {
                Text("Own goal", style = MaterialTheme.typography.titleMedium)
                Text(p.scorer?.name ?: "", color = team?.color?.lighter() ?: Color.White)
            } else {
                Text(p.scorer?.name ?: "", style = MaterialTheme.typography.titleMedium,
                    color = team?.color?.lighter() ?: Color.White)
                Text("assist: ${p.assist?.name ?: "none"}")
            }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm ($left)", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun MatchViewModel.describe(e: MatchEvent): String {
    if (e.status == EventStatus.PARTIAL) return "PARTIAL"
    val team = teams.firstOrNull { it.id == e.teamId }?.name
    return "${if (e.type == GoalType.OWN_GOAL) "OWN " else ""}$team " +
        "${player(e.scorerId)?.name} (${player(e.assistId)?.name ?: "-"})"
}

/** Every event in memory, newest first. Tap one to edit it. */
@Composable
fun LogScreen(vm: MatchViewModel, onEdit: (MatchEvent) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(contentPadding = padding, state = listState) {
            header(spec) { Text("Events (${vm.events.size})") }
            for (e in vm.events.sortedByDescending { it.occurredAt }) {
                item {
                    FilledTonalButton(
                        onClick = { onEdit(e) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        Text("${clock.format(e.occurredAt)}\n${vm.describe(e)}",
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Edit one event: move it in time, change who scored/assisted, or delete it. */
@Composable
fun EditScreen(vm: MatchViewModel, eventId: String, onChangePlayers: () -> Unit, onDeleted: () -> Unit) {
    val e = vm.events.firstOrNull { it.eventId == eventId } ?: return
    var confirm by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(contentPadding = padding, state = listState) {
            header(spec) { Text(clock.format(e.occurredAt)) }
            item {
                Text(vm.describe(e), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
            }
            for (pair in listOf(-60L to 60L, -10L to 10L)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (sec in pair.toList()) {
                            FilledTonalButton(onClick = { vm.shiftTime(eventId, sec) }, modifier = Modifier.weight(1f)) {
                                val label = if (kotlin.math.abs(sec) == 60L) "1m" else "10s"
                                Text("${if (sec < 0) "−" else "+"}$label", maxLines = 1)
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = onChangePlayers,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text("Change players", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
            item {
                Button(
                    onClick = { if (confirm) { vm.delete(eventId); onDeleted() } else confirm = true },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) {
                    Text(if (confirm) "Tap again to delete" else "Delete",
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun TransformingLazyColumnScope.header(spec: TransformationSpec, content: @Composable () -> Unit) {
    item {
        ListHeader(
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        ) { content() }
    }
}

/** Team colours are dark enough for buttons; this makes them readable as text on black. */
private fun Color.lighter() = Color(
    red = red + (1f - red) * 0.55f, green = green + (1f - green) * 0.55f, blue = blue + (1f - blue) * 0.55f)
