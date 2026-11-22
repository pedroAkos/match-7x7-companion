package com.example.match_7x7_companiom.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.match_7x7_companiom.data.AppDatabase
import com.example.match_7x7_companiom.data.LocalMatch
import com.example.match_7x7_companiom.data.MatchSummary
import com.example.match_7x7_companiom.data.PlayerRow
import com.example.match_7x7_companiom.data.Server
import com.example.match_7x7_companiom.data.ServerException
import com.example.match_7x7_companiom.data.TeamRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** Where the match on the watch is in its life. Derived from the stored [LocalMatch]. */
enum class Phase { NO_MATCH, LOADED, RUNNING, ENDED, SYNCED }

/** State of a call to the server. */
sealed interface Remote<out T> {
    data object Idle : Remote<Nothing>
    data object Loading : Remote<Nothing>
    data class Failed(val message: String) : Remote<Nothing>
    data class Done<T>(val value: T) : Remote<T>
}

/** A goal whose tap flow is still in progress. */
data class PendingGoal(
    val eventId: String,
    val occurredAt: Instant,
    val type: GoalType,
    val team: Team? = null,      // team of the scorer (for an own goal: the team that conceded)
    val scorer: Player? = null,
    val assist: Player? = null,   // null = None
    val editing: Boolean = false, // true = re-entering the pickers for an existing event
)

private val TEAM_COLORS = listOf(Color(0xFFB3261E), Color(0xFF1F5FBF))

/**
 * Room is the single source of truth for the match on the watch: every change is written to
 * the database and the state here follows it, so everything survives restarts.
 */
class MatchViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).match()

    /** False until the database has answered once (avoids flashing "no match" at startup). */
    var ready by mutableStateOf(false)
        private set
    var match by mutableStateOf<LocalMatch?>(null)
        private set
    var teams by mutableStateOf<List<Team>>(emptyList())
        private set
    var players by mutableStateOf<List<Player>>(emptyList())
        private set
    var events by mutableStateOf<List<MatchEvent>>(emptyList())
        private set
    var pending by mutableStateOf<PendingGoal?>(null)
        private set

    var matchList by mutableStateOf<Remote<List<MatchSummary>>>(Remote.Idle)
        private set
    var loadState by mutableStateOf<Remote<Unit>>(Remote.Idle)
        private set
    var syncState by mutableStateOf<Remote<MatchSummary>>(Remote.Idle)
        private set

    init {
        viewModelScope.launch { dao.observeMatch().collect { match = it; ready = true } }
        viewModelScope.launch {
            dao.observeTeams().collect { rows ->
                teams = rows.map { Team(it.teamId, it.name, TEAM_COLORS.getOrElse(it.position) { Color.Gray }) }
            }
        }
        viewModelScope.launch {
            dao.observePlayers().collect { rows -> players = rows.map { Player(it.playerId, it.name, it.teamId) } }
        }
        viewModelScope.launch { dao.observeEvents().collect { events = it } }
    }

    val phase: Phase
        get() {
            val m = match ?: return Phase.NO_MATCH
            return when {
                m.kickoffAt == null -> Phase.LOADED
                m.endedAt == null -> Phase.RUNNING
                m.syncedRevision == m.revision -> Phase.SYNCED
                else -> Phase.ENDED
            }
        }
    val kickoffAt: Instant? get() = match?.kickoffAt
    val endedAt: Instant? get() = match?.endedAt
    val running: Boolean get() = phase == Phase.RUNNING

    fun player(id: String?): Player? = players.firstOrNull { it.id == id }

    fun score(teamId: String): Int =
        events.count { it.status == EventStatus.COMPLETE && it.teamId == teamId }

    // ---- match life cycle ----

    fun kickOff() {
        viewModelScope.launch { dao.kickOff(Instant.now().toEpochMilli()) }
    }

    fun endMatch() {
        viewModelScope.launch { dao.endMatch(Instant.now().toEpochMilli()) }
    }

    /** Back to "no match": before kick-off (Unload) or after a confirmed sync (Close). */
    fun removeMatch() {
        if (phase != Phase.LOADED && phase != Phase.SYNCED) return
        pending = null
        syncState = Remote.Idle
        viewModelScope.launch { dao.clear() }
    }

    // ---- talking to the server ----

    fun refreshList() {
        matchList = Remote.Loading
        viewModelScope.launch {
            matchList = try {
                Remote.Done(Server.listMatches())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Remote.Failed(describe(e))
            }
        }
    }

    /** Fetches the roster and stores it; [onLoaded] runs once the match is on the watch. */
    fun loadMatch(id: String, onLoaded: () -> Unit) {
        if (loadState is Remote.Loading || phase != Phase.NO_MATCH) return
        loadState = Remote.Loading
        viewModelScope.launch {
            try {
                val d = Server.getMatch(id)
                dao.load(
                    LocalMatch(d.id, d.scheduledAt),
                    d.teams.mapIndexed { i, t -> TeamRow(t.id, t.name, i) },
                    d.players.mapIndexed { i, p -> PlayerRow(p.id, p.name, p.teamId, i) },
                )
                loadState = Remote.Idle
                onLoaded()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                loadState = Remote.Failed(describe(e))
            }
        }
    }

    /**
     * Uploads the whole recorded match (the server replaces its copy). The match counts as
     * synced only if the server lists every event id that was sent.
     */
    fun sync() {
        if (phase != Phase.ENDED || syncState is Remote.Loading) return
        syncState = Remote.Loading
        viewModelScope.launch {
            try {
                val m = dao.match() ?: return@launch
                val sent = dao.events()
                val s = Server.putResult(m.matchId, m.kickoffAt!!, m.endedAt!!, sent)
                if (s.eventIds.toSet() != sent.map { it.eventId }.toSet()) {
                    throw ServerException("Server stored ${s.eventIds.size} of ${sent.size} events")
                }
                dao.markSynced(m.revision, s.score[teams[0].id], s.score[teams[1].id], s.goalCount, s.partialCount)
                syncState = Remote.Done(s)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                syncState = Remote.Failed(describe(e))
            }
        }
    }

    private fun describe(e: Exception) = if (e is ServerException) e.message ?: "Server error" else "No connection"

    // ---- the goal flow ----

    /** Step 1 of the flow: stamps occurredAt now. False if a goal is already in progress. */
    fun startGoal(type: GoalType): Boolean {
        val m = match
        if (pending != null || !running || m == null) return false
        val p = PendingGoal(UUID.randomUUID().toString(), Instant.now(), type)
        pending = p
        // The goal exists from the first tap: a partial event is stored at once, so even a
        // crash or kill mid-flow loses nothing. Confirm replaces it with the full event.
        save(MatchEvent(p.eventId, m.matchId, p.type, null, null, null, p.occurredAt, EventStatus.PARTIAL))
        return true
    }

    /** Edit an existing event: runs the same pickers, then replaces the event. */
    fun startEdit(eventId: String): Boolean {
        if (pending != null) return false
        val e = events.firstOrNull { it.eventId == eventId } ?: return false
        pending = PendingGoal(e.eventId, e.occurredAt, e.type, editing = true)
        return true
    }

    fun pickTeam(team: Team) {
        pending = pending?.copy(team = team, scorer = null)
    }

    fun pickScorer(player: Player) {
        pending = pending?.copy(scorer = player, assist = null)
    }

    /** Assist chosen (null = None); only recorded, the goal is committed by [complete]. */
    fun pickAssist(player: Player?) {
        pending = pending?.copy(assist = player)
    }

    /** Confirm pressed: commits the event to the log (replaces it when editing). */
    fun complete() {
        val p = pending ?: return
        val m = match ?: return
        val scorer = p.scorer ?: return
        val teamId = if (p.type == GoalType.GOAL) scorer.teamId
        else teams.first { it.id != scorer.teamId }.id
        save(MatchEvent(p.eventId, m.matchId, p.type, teamId, scorer.id, p.assist?.id,
            p.occurredAt, EventStatus.COMPLETE))
        pending = null
    }

    /** Flow left unfinished. A new goal already has its partial event stored; an edit keeps the old event. */
    fun abandonPending() {
        pending = null
    }

    /** Moves an event in time (never past now). */
    fun shiftTime(eventId: String, seconds: Long) {
        viewModelScope.launch { dao.shift(eventId, seconds * 1000, Instant.now().toEpochMilli()) }
    }

    fun delete(eventId: String) {
        viewModelScope.launch { dao.delete(eventId) }
    }

    private fun save(event: MatchEvent) {
        viewModelScope.launch { dao.upsert(event) }
    }
}
