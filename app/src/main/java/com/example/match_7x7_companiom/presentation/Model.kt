package com.example.match_7x7_companiom.presentation

import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

enum class GoalType { GOAL, OWN_GOAL }
enum class EventStatus { COMPLETE, PARTIAL }

data class Team(val id: String, val name: String, val color: Color)
data class Player(val id: String, val name: String, val teamId: String)

@Entity(tableName = "events")
data class MatchEvent(
    @PrimaryKey val eventId: String,
    val matchId: String,
    val type: GoalType,
    val teamId: String?,      // null for partial events
    val scorerId: String?,    // null for partial events
    val assistId: String?,    // null = none or not filled in
    val occurredAt: Instant,  // UTC, first tap
    val status: EventStatus,
)
