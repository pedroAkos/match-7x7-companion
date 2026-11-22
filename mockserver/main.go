// Mock server for the Match 7x7 watch app: a dumb, in-memory state holder.
//
//	GET /matches              list of match summaries
//	GET /matches/{id}         one match with its roster
//	PUT /matches/{id}/result  replace the recorded result (idempotent); replies with a summary
//
// State lives in memory only: restarting the server resets it to the seed data below.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type Team struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type Player struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	TeamID string `json:"teamId"`
}

type Event struct {
	EventID    string  `json:"eventId"`
	MatchID    string  `json:"matchId"`
	Type       string  `json:"type"` // GOAL | OWN_GOAL
	TeamID     *string `json:"teamId"`
	ScorerID   *string `json:"scorerId"`
	AssistID   *string `json:"assistId"`
	OccurredAt string  `json:"occurredAt"`
	Status     string  `json:"status"` // COMPLETE | PARTIAL
}

// Result is exactly what the watch recorded; a PUT replaces it as a whole.
type Result struct {
	KickoffAt string  `json:"kickoffAt"`
	EndedAt   string  `json:"endedAt"`
	Events    []Event `json:"events"`
}

type Match struct {
	ID          string   `json:"id"`
	ScheduledAt string   `json:"scheduledAt"`
	Teams       []Team   `json:"teams"`
	Players     []Player `json:"players"`
	Result      *Result  `json:"-"`
}

// Summary is what the watch lists and what a PUT answers with.
type Summary struct {
	ID           string         `json:"id"`
	ScheduledAt  string         `json:"scheduledAt"`
	Status       string         `json:"status"` // SCHEDULED | FINISHED
	Teams        []Team         `json:"teams"`
	PlayerCount  int            `json:"playerCount"`
	Score        map[string]int `json:"score"` // teamId -> goals, only when FINISHED
	GoalCount    int            `json:"goalCount"`
	PartialCount int            `json:"partialCount"`
	KickoffAt    string         `json:"kickoffAt,omitempty"`
	EndedAt      string         `json:"endedAt,omitempty"`
	EventIDs     []string       `json:"eventIds"`
}

var (
	mu      sync.Mutex
	matches = map[string]*Match{}
)

func summarize(m *Match) Summary {
	s := Summary{ID: m.ID, ScheduledAt: m.ScheduledAt, Status: "SCHEDULED", Teams: m.Teams,
		PlayerCount: len(m.Players), EventIDs: []string{}}
	if m.Result == nil {
		return s
	}
	s.Status = "FINISHED"
	s.KickoffAt, s.EndedAt = m.Result.KickoffAt, m.Result.EndedAt
	s.Score = map[string]int{}
	for _, t := range m.Teams {
		s.Score[t.ID] = 0
	}
	for _, e := range m.Result.Events {
		s.EventIDs = append(s.EventIDs, e.EventID)
		if e.Status == "PARTIAL" || e.TeamID == nil {
			s.PartialCount++
			continue
		}
		s.GoalCount++
		s.Score[*e.TeamID]++
	}
	return s
}

func roster(teamID string, names ...string) []Player {
	var ps []Player
	for i, n := range names {
		ps = append(ps, Player{ID: fmt.Sprintf("%s%d", strings.ToLower(teamID), i+1), Name: n, TeamID: teamID})
	}
	return ps
}

func seed() {
	day := time.Now().UTC().Truncate(24 * time.Hour)
	at := func(d int, h int) string {
		return day.AddDate(0, 0, d).Add(time.Duration(h) * time.Hour).Format(time.RFC3339)
	}
	add := func(m *Match) { matches[m.ID] = m }

	add(&Match{ID: "m1", ScheduledAt: at(0, 19),
		Teams: []Team{{"m1-a", "Reds"}, {"m1-b", "Blues"}},
		Players: append(roster("m1-a", "João", "Pedro", "Tiago", "Rui", "Miguel", "Bruno", "Diogo"),
			roster("m1-b", "Jack", "Tom", "Sam", "Ben", "Luke", "Dan", "Max")...)})
	add(&Match{ID: "m2", ScheduledAt: at(7, 19),
		Teams: []Team{{"m2-a", "Oranges"}, {"m2-b", "Purples"}},
		Players: append(roster("m2-a", "Ana", "Inês", "Rita", "Sofia", "Joe", "Ed", "Finn"),
			roster("m2-b", "Lia", "Eva", "Mia", "Zoe", "Nuno", "Hugo", "Leo")...)})

	// A match that was already played and uploaded, so the list shows a finished state.
	m3 := &Match{ID: "m3", ScheduledAt: at(-7, 19),
		Teams: []Team{{"m3-a", "Greens"}, {"m3-b", "Yellows"}},
		Players: append(roster("m3-a", "Carlos", "Ivo", "Paulo", "Vasco", "Tim", "Ollie", "Sean"),
			roster("m3-b", "Rafa", "Gil", "Davi", "Hal", "Joel", "Ray", "Kit")...)}
	ptr := func(s string) *string { return &s }
	g := func(id, team, scorer, assist, when string) Event {
		e := Event{EventID: id, MatchID: "m3", Type: "GOAL", TeamID: ptr(team), ScorerID: ptr(scorer),
			OccurredAt: when, Status: "COMPLETE"}
		if assist != "" {
			e.AssistID = ptr(assist)
		}
		return e
	}
	m3.Result = &Result{KickoffAt: at(-7, 19), EndedAt: at(-7, 20), Events: []Event{
		g("m3-e1", "m3-a", "m3-a1", "m3-a2", at(-7, 19)),
		g("m3-e2", "m3-b", "m3-b3", "", at(-7, 19)),
		g("m3-e3", "m3-a", "m3-a4", "m3-a1", at(-7, 20)),
	}}
	add(m3)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func listMatches(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()
	out := []Summary{}
	for _, m := range matches {
		out = append(out, summarize(m))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ScheduledAt < out[j].ScheduledAt })
	log.Printf("GET /matches -> %d matches", len(out))
	writeJSON(w, 200, out)
}

func getMatch(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	defer mu.Unlock()
	m := matches[r.PathValue("id")]
	if m == nil {
		http.Error(w, "unknown match", 404)
		return
	}
	log.Printf("GET /matches/%s -> %d players", m.ID, len(m.Players))
	writeJSON(w, 200, m)
}

func putResult(w http.ResponseWriter, r *http.Request) {
	var res Result
	if err := json.NewDecoder(r.Body).Decode(&res); err != nil {
		http.Error(w, "bad json: "+err.Error(), 400)
		return
	}
	mu.Lock()
	defer mu.Unlock()
	m := matches[r.PathValue("id")]
	if m == nil {
		http.Error(w, "unknown match", 404)
		return
	}
	if res.Events == nil {
		res.Events = []Event{}
	}
	m.Result = &res
	s := summarize(m)
	log.Printf("PUT /matches/%s/result: kickoff %s, end %s, %d events (%d goals, %d partial), score %v",
		m.ID, res.KickoffAt, res.EndedAt, len(res.Events), s.GoalCount, s.PartialCount, s.Score)
	for _, e := range res.Events {
		log.Printf("    %s  %-8s %-7s team=%s scorer=%s assist=%s at %s", short(e.EventID), e.Type, e.Status,
			deref(e.TeamID), deref(e.ScorerID), deref(e.AssistID), e.OccurredAt)
	}
	writeJSON(w, 200, s)
}

func deref(s *string) string {
	if s == nil {
		return "-"
	}
	return *s
}

func short(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func main() {
	port := flag.Int("port", 8080, "port to listen on")
	flag.Parse()
	seed()
	mux := http.NewServeMux()
	mux.HandleFunc("GET /matches", listMatches)
	mux.HandleFunc("GET /matches/{id}", getMatch)
	mux.HandleFunc("PUT /matches/{id}/result", putResult)
	log.Printf("mock server on :%d (watch emulator: http://10.0.2.2:%d)", *port, *port)
	log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", *port), mux))
}
