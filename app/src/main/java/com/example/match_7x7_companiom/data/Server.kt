package com.example.match_7x7_companiom.data

import com.example.match_7x7_companiom.presentation.GoalType
import com.example.match_7x7_companiom.presentation.MatchEvent
import com.example.match_7x7_companiom.presentation.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class TeamInfo(val id: String, val name: String)

/** One row of the server's match list; also the server's answer to an uploaded result. */
data class MatchSummary(
    val id: String,
    val scheduledAt: Instant,
    val status: String, // SCHEDULED | FINISHED
    val teams: List<TeamInfo>,
    val playerCount: Int,
    val score: Map<String, Int>, // teamId -> goals; empty unless FINISHED
    val goalCount: Int,
    val partialCount: Int,
    val eventIds: List<String>,
) {
    val finished get() = status == "FINISHED"
}

data class MatchDetail(val id: String, val scheduledAt: Instant, val teams: List<TeamInfo>, val players: List<Player>)

class ServerException(message: String) : IOException(message)

/** Plain HTTP + org.json (both part of Android), so no extra dependency. */
object Server {
    /** The Android emulator reaches the Mac at 10.0.2.2. */
    const val BASE = "http://10.0.2.2:8080"

    suspend fun listMatches(): List<MatchSummary> =
        JSONArray(call("GET", "/matches")).objects().map(::summary)

    suspend fun getMatch(id: String): MatchDetail {
        val j = JSONObject(call("GET", "/matches/$id"))
        return MatchDetail(
            j.getString("id"), Instant.parse(j.getString("scheduledAt")),
            j.getJSONArray("teams").objects().map { TeamInfo(it.getString("id"), it.getString("name")) },
            j.getJSONArray("players").objects().map { Player(it.getString("id"), it.getString("name"), it.getString("teamId")) },
        )
    }

    /** Replaces the server's copy of the match result (idempotent, safe to retry). */
    suspend fun putResult(matchId: String, kickoffAt: Instant, endedAt: Instant, events: List<MatchEvent>): MatchSummary {
        val body = JSONObject()
            .put("kickoffAt", kickoffAt.toString())
            .put("endedAt", endedAt.toString())
            .put("events", JSONArray(events.map { e ->
                JSONObject()
                    .put("eventId", e.eventId).put("matchId", e.matchId)
                    .put("type", e.type.name)
                    .put("teamId", e.teamId ?: JSONObject.NULL)
                    .put("scorerId", e.scorerId ?: JSONObject.NULL)
                    .put("assistId", e.assistId ?: JSONObject.NULL)
                    .put("occurredAt", e.occurredAt.toString())
                    .put("status", e.status.name)
            }))
        return summary(JSONObject(call("PUT", "/matches/$matchId/result", body.toString())))
    }

    private fun summary(j: JSONObject): MatchSummary {
        val score = j.optJSONObject("score")
        return MatchSummary(
            j.getString("id"), Instant.parse(j.getString("scheduledAt")), j.getString("status"),
            j.getJSONArray("teams").objects().map { TeamInfo(it.getString("id"), it.getString("name")) },
            j.getInt("playerCount"),
            score?.keys()?.asSequence()?.associateWith { score.getInt(it) } ?: emptyMap(),
            j.getInt("goalCount"), j.getInt("partialCount"),
            j.getJSONArray("eventIds").let { a -> List(a.length()) { a.getString(it) } },
        )
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private suspend fun call(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 4000
                c.readTimeout = 8000
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) throw ServerException("Server error $code")
                text
            } finally {
                c.disconnect()
            }
        }
}
