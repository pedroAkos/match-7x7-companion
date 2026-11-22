package com.example.match_7x7_companiom.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.example.match_7x7_companiom.presentation.MatchEvent
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The one match currently on the watch (no row = no match loaded). The phase is derived:
 * kickoffAt null = loaded, endedAt null = running, otherwise ended; ended is "synced" when
 * [syncedRevision] equals [revision]. Every change to the recording bumps [revision], so an
 * edit after a sync makes the match unsynced again.
 */
@Entity(tableName = "local_match")
data class LocalMatch(
    @PrimaryKey val matchId: String,
    val scheduledAt: Instant,
    val kickoffAt: Instant? = null,
    val endedAt: Instant? = null,
    val revision: Int = 0,
    val syncedRevision: Int = -1,
    // What the server said it stored at the last successful sync.
    val serverScoreA: Int? = null,
    val serverScoreB: Int? = null,
    val serverGoals: Int? = null,
    val serverPartials: Int? = null,
)

@Entity(tableName = "teams")
data class TeamRow(@PrimaryKey val teamId: String, val name: String, val position: Int)

@Entity(tableName = "players")
data class PlayerRow(@PrimaryKey val playerId: String, val name: String, val teamId: String, val position: Int)

class Converters {
    @TypeConverter fun toMillis(t: Instant?): Long? = t?.toEpochMilli()
    @TypeConverter fun fromMillis(ms: Long?): Instant? = ms?.let { Instant.ofEpochMilli(it) }
}

@Dao
abstract class MatchDao {
    @Query("SELECT * FROM local_match LIMIT 1") abstract fun observeMatch(): Flow<LocalMatch?>
    @Query("SELECT * FROM teams ORDER BY position") abstract fun observeTeams(): Flow<List<TeamRow>>
    @Query("SELECT * FROM players ORDER BY position") abstract fun observePlayers(): Flow<List<PlayerRow>>
    @Query("SELECT * FROM events") abstract fun observeEvents(): Flow<List<MatchEvent>>

    @Query("SELECT * FROM local_match LIMIT 1") abstract suspend fun match(): LocalMatch?
    @Query("SELECT * FROM events") abstract suspend fun events(): List<MatchEvent>

    @Insert abstract suspend fun insertMatch(m: LocalMatch)
    @Insert abstract suspend fun insertTeams(t: List<TeamRow>)
    @Insert abstract suspend fun insertPlayers(p: List<PlayerRow>)
    @Query("DELETE FROM local_match") abstract suspend fun deleteMatch()
    @Query("DELETE FROM teams") abstract suspend fun deleteTeams()
    @Query("DELETE FROM players") abstract suspend fun deletePlayers()
    @Query("DELETE FROM events") abstract suspend fun deleteEvents()

    /** Replaces everything on the watch with a freshly loaded match. */
    @Transaction
    open suspend fun load(m: LocalMatch, teams: List<TeamRow>, players: List<PlayerRow>) {
        clear()
        insertMatch(m); insertTeams(teams); insertPlayers(players)
    }

    /** Back to "no match loaded". */
    @Transaction
    open suspend fun clear() {
        deleteMatch(); deleteTeams(); deletePlayers(); deleteEvents()
    }

    @Query("UPDATE local_match SET revision = revision + 1") abstract suspend fun bump()

    @Upsert abstract suspend fun upsertRaw(event: MatchEvent)
    @Query("DELETE FROM events WHERE eventId = :eventId") abstract suspend fun deleteRaw(eventId: String)
    @Query("UPDATE events SET occurredAt = MIN(occurredAt + :deltaMs, :nowMs) WHERE eventId = :eventId")
    abstract suspend fun shiftRaw(eventId: String, deltaMs: Long, nowMs: Long)

    @Transaction open suspend fun upsert(event: MatchEvent) { upsertRaw(event); bump() }
    @Transaction open suspend fun delete(eventId: String) { deleteRaw(eventId); bump() }
    /** Moves an event in time, never past [nowMs]. */
    @Transaction open suspend fun shift(eventId: String, deltaMs: Long, nowMs: Long) { shiftRaw(eventId, deltaMs, nowMs); bump() }

    @Query("UPDATE local_match SET kickoffAt = :nowMs, revision = revision + 1 WHERE kickoffAt IS NULL")
    abstract suspend fun kickOff(nowMs: Long)

    @Query("UPDATE local_match SET endedAt = :nowMs, revision = revision + 1 WHERE kickoffAt IS NOT NULL AND endedAt IS NULL")
    abstract suspend fun endMatch(nowMs: Long)

    /** [revision] is the one that was sent; if edits happened meanwhile the match stays unsynced. */
    @Query("UPDATE local_match SET syncedRevision = :revision, serverScoreA = :a, serverScoreB = :b, serverGoals = :goals, serverPartials = :partials")
    abstract suspend fun markSynced(revision: Int, a: Int?, b: Int?, goals: Int, partials: Int)
}

@Database(
    entities = [LocalMatch::class, TeamRow::class, PlayerRow::class, MatchEvent::class],
    version = 3, exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun match(): MatchDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "match.db")
                // Development only: a schema change wipes the local data.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { instance = it }
        }
    }
}
