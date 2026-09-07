package com.onestake.selectionai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class HybridFootballClient(@Suppress("UNUSED_PARAMETER") private val apiKey: String) {
    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val diretta = runCatching { DirettaMobileClient().enrich(candidate, date) }
            .getOrElse { candidate.copy(notes = "Diretta non disponibile: ${it.message}") }

        val needsFallback = diretta.fixtureId == null || diretta.leagueName.isNullOrBlank() ||
            diretta.homeRank == null || diretta.awayRank == null ||
            diretta.homeFormPoints5 == null || diretta.awayFormPoints5 == null ||
            diretta.homeHomeWinRate == null || diretta.awayAwayLossRate == null

        if (!needsFallback) return diretta

        return runCatching { SofascoreFallbackClient().enrich(diretta, date) }
            .getOrElse { diretta.copy(notes = merge(diretta.notes, "Fallback SofaScore non disponibile: ${it.message}")) }
    }

    private fun merge(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }
}

private class SofascoreFallbackClient {
    private val base = "https://www.sofascore.com/api/v1"

    private data class TeamHit(val id: Int, val name: String)
    private data class Event(
        val id: Int,
        val home: String,
        val away: String,
        val homeId: Int,
        val awayId: Int,
        val homeGoals: Int?,
        val awayGoals: Int?,
        val uniqueTournamentId: Int?,
        val tournamentName: String?,
        val seasonId: Int?
    )

    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val homeTeam = findTeam(candidate.home)
            ?: return candidate.copy(notes = merge(candidate.notes, "Fallback SofaScore: squadra casa non identificata"))

        val homeNext = loadEvents(homeTeam.id, "next")
        val homeLast = loadEvents(homeTeam.id, "last")

        var awayTeam = findTeam(candidate.away)
        var directEvent: Event? = null

        if (awayTeam != null) {
            val awayNext = loadEvents(awayTeam.id, "next")
            val awayLast = loadEvents(awayTeam.id, "last")
            directEvent = (homeNext + awayNext + homeLast + awayLast)
                .distinctBy { it.id }
                .firstOrNull { eventMatches(candidate, it) }
        }

        if (directEvent == null) {
            directEvent = (homeNext + homeLast)
                .distinctBy { it.id }
                .filter { teamMatches(candidate.home, it.home) || teamMatches(candidate.home, it.away) }
                .maxByOrNull { opponentSimilarity(candidate, it) }
                ?.takeIf { opponentSimilarity(candidate, it) >= 0.52 && categoryCompatible(candidate.away, opponentName(candidate, it)) }

            if (directEvent != null) {
                val opponentIsAway = teamMatches(candidate.home, directEvent.home)
                awayTeam = if (opponentIsAway) TeamHit(directEvent.awayId, directEvent.away)
                else TeamHit(directEvent.homeId, directEvent.home)
            }
        }

        if (awayTeam == null) {
            return candidate.copy(notes = merge(candidate.notes, "Fallback SofaScore: squadra ospite non identificata"))
        }

        val awayLast = loadEvents(awayTeam.id, "last")

        var homeRank = candidate.homeRank
        var awayRank = candidate.awayRank
        var leagueName = candidate.leagueName
        var fixtureId = candidate.fixtureId
        var leagueId = candidate.leagueId
        var season = candidate.season
        var homeId = candidate.homeTeamId
        var awayId = candidate.awayTeamId

        var referenceEvent = directEvent
        if (referenceEvent == null && !candidate.leagueName.isNullOrBlank()) {
            referenceEvent = (homeNext + homeLast + awayLast)
                .distinctBy { it.id }
                .filter { it.uniqueTournamentId != null && it.seasonId != null && !it.tournamentName.isNullOrBlank() }
                .maxByOrNull { similarity(candidate.leagueName ?: "", it.tournamentName ?: "") }
                ?.takeIf { similarity(candidate.leagueName ?: "", it.tournamentName ?: "") >= 0.45 }
        }

        if (directEvent != null) {
            fixtureId = directEvent.id.takeIf { it > 0 } ?: fixtureId
            leagueName = directEvent.tournamentName ?: leagueName
            leagueId = directEvent.uniqueTournamentId ?: leagueId
            season = directEvent.seasonId ?: season
            homeId = directEvent.homeId.takeIf { it > 0 } ?: homeTeam.id
            awayId = directEvent.awayId.takeIf { it > 0 } ?: awayTeam.id
        }

        if ((homeRank == null || awayRank == null) && referenceEvent?.uniqueTournamentId != null && referenceEvent.seasonId != null) {
            val ranks = loadStandings(referenceEvent.uniqueTournamentId, referenceEvent.seasonId)
            if (homeRank == null) homeRank = findRank(candidate.home, ranks)
            if (awayRank == null) awayRank = findRank(awayTeam.name, ranks)
            if (leagueName.isNullOrBlank()) leagueName = referenceEvent.tournamentName
            if (leagueId == null) leagueId = referenceEvent.uniqueTournamentId
            if (season == null) season = referenceEvent.seasonId
        }

        var homePts = candidate.homeFormPoints5
        var awayPts = candidate.awayFormPoints5
        var homeRate = candidate.homeHomeWinRate
        var awayLossRate = candidate.awayAwayLossRate

        if (homePts == null) homePts = pointsLastFive(homeTeam.name, homeLast)
        if (awayPts == null) awayPts = pointsLastFive(awayTeam.name, awayLast)
        if (homeRate == null) homeRate = homeWinRate(homeTeam.name, homeLast)
        if (awayLossRate == null) awayLossRate = awayLossRate(awayTeam.name, awayLast)

        val syntheticFixture = fixtureId ?: -abs((candidate.home + "|" + candidate.away + "|" + date).hashCode()).coerceAtLeast(1)
        val syntheticLeague = leagueId ?: -abs((leagueName ?: "SofaScore").hashCode()).coerceAtLeast(1)

        val missing = mutableListOf<String>()
        if (directEvent == null) missing += "identificazione partita"
        if (homeRank == null || awayRank == null) missing += "classifica"
        if (homeRate == null) missing += "rendimento casa"
        if (awayLossRate == null) missing += "rendimento trasferta"
        if (homePts == null || awayPts == null) missing += "ultime 5"

        val aliasNote = if (!teamMatches(candidate.away, awayTeam.name)) " · ospite riconosciuta come ${awayTeam.name}" else ""
        val note = if (missing.isEmpty()) {
            "Fallback SofaScore esteso · partita identificata · dati completi$aliasNote"
        } else {
            "Fallback SofaScore esteso · mancanti: ${missing.distinct().joinToString(", ")}$aliasNote"
        }

        return candidate.copy(
            fixtureId = syntheticFixture,
            leagueId = syntheticLeague,
            leagueName = leagueName ?: candidate.leagueName,
            season = season,
            homeTeamId = homeId ?: homeTeam.id,
            awayTeamId = awayId ?: awayTeam.id,
            homeRank = homeRank,
            awayRank = awayRank,
            homeFormPoints5 = homePts,
            awayFormPoints5 = awayPts,
            homeHomeWinRate = homeRate,
            awayAwayLossRate = awayLossRate,
            notes = merge(candidate.notes, note)
        )
    }

    private fun eventMatches(c: MatchCandidate, e: Event): Boolean =
        teamMatches(c.home, e.home) && teamMatches(c.away, e.away)

    private fun opponentName(c: MatchCandidate, e: Event): String =
        if (teamMatches(c.home, e.home)) e.away else e.home

    private fun opponentSimilarity(c: MatchCandidate, e: Event): Double =
        similarity(canonical(c.away), canonical(opponentName(c, e)))

    private fun findTeam(expected: String): TeamHit? {
        findTeamLegacy(expected)?.let { return it }
        return findTeamEnhanced(expected)
    }

    private fun findTeamLegacy(expected: String): TeamHit? {
        val q = URLEncoder.encode(expected, "UTF-8")
        val json = runCatching { JSONObject(fetch("$base/search/all?q=$q")) }.getOrNull() ?: return null
        val results = json.optJSONArray("results") ?: return null
        var best: TeamHit? = null
        var bestScore = 0.0
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            val entity = item.optJSONObject("entity") ?: item.optJSONObject("team") ?: continue
            val name = entity.optString("name", "").trim()
            val id = entity.optInt("id", 0)
            if (name.isBlank() || id <= 0) continue
            if (type.isNotBlank() && !type.equals("team", true)) continue
            if (!categoryCompatible(expected, name)) continue
            val sport = entity.optJSONObject("sport")?.optString("name", "") ?: ""
            if (sport.isNotBlank() && !sport.equals("football", true) && !sport.equals("calcio", true)) continue
            val score = similarity(expected, name)
            if (score > bestScore) { bestScore = score; best = TeamHit(id, name) }
        }
        return best?.takeIf { bestScore >= 0.68 }
    }

    private fun findTeamEnhanced(expected: String): TeamHit? {
        val queries = searchVariants(expected)
        var best: TeamHit? = null
        var bestScore = 0.0
        for (query in queries) {
            val q = URLEncoder.encode(query, "UTF-8")
            val json = runCatching { JSONObject(fetch("$base/search/all?q=$q")) }.getOrNull() ?: continue
            val results = json.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val type = item.optString("type", "")
                val entity = item.optJSONObject("entity") ?: item.optJSONObject("team") ?: continue
                val name = entity.optString("name", "").trim()
                val id = entity.optInt("id", 0)
                if (name.isBlank() || id <= 0) continue
                if (type.isNotBlank() && !type.equals("team", true)) continue
                if (!categoryCompatible(expected, name)) continue
                val sport = entity.optJSONObject("sport")?.optString("name", "") ?: ""
                if (sport.isNotBlank() && !sport.equals("football", true) && !sport.equals("calcio", true)) continue
                val score = similarity(canonical(expected), canonical(name))
                if (score > bestScore) { bestScore = score; best = TeamHit(id, name) }
            }
        }
        return best?.takeIf { bestScore >= 0.52 }
    }

    private fun searchVariants(name: String): Set<String> {
        val youth = youthCategory(name)
        val c = canonical(name)
        val noAl = c.replace(Regex("(?i)\\bal\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val noSuffix = c.replace(Regex("(?i)\\b(as|ssc|fk|sk|ks|club|sport|sporting|united|city)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val short = c.split(' ').filter { it.length > 2 && !it.matches(Regex("(?i)U\\d+")) }.take(2).joinToString(" ")
        val out = linkedSetOf(name, c, alias(name), noAl, noSuffix, short)
        if (youth != null) {
            listOf(noAl, noSuffix, short).filter { it.isNotBlank() }.forEach { out += "$it $youth" }
        }
        return out.filter { it.isNotBlank() }.toSet()
    }

    private fun alias(name: String): String {
        val n = canonical(name)
        return when {
            n.contains("paok thessaloniki") -> n.replace("paok thessaloniki", "paok")
            n.contains("caykur rizespor") -> n.replace("caykur rizespor", "rizespor")
            else -> n
        }
    }

    private fun canonical(name: String): String = name
        .replace(Regex("(?i)\\bthessaloniki\\b"), " ")
        .replace(Regex("(?i)\\bcaykur\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun loadEvents(teamId: Int, mode: String): List<Event> {
        val out = mutableListOf<Event>()
        for (page in 0..3) {
            val json = runCatching { JSONObject(fetch("$base/team/$teamId/events/$mode/$page")) }.getOrNull() ?: continue
            val arr = json.optJSONArray("events") ?: continue
            parseEvents(arr, out)
            if (out.size >= 30 || !json.optBoolean("hasNextPage", false)) break
        }
        return out
    }

    private fun parseEvents(arr: JSONArray, out: MutableList<Event>) {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val homeObj = e.optJSONObject("homeTeam") ?: continue
            val awayObj = e.optJSONObject("awayTeam") ?: continue
            val home = homeObj.optString("name", "").trim()
            val away = awayObj.optString("name", "").trim()
            if (home.isBlank() || away.isBlank()) continue
            val tournament = e.optJSONObject("tournament")
            val uniqueTournament = tournament?.optJSONObject("uniqueTournament")
            val seasonObj = e.optJSONObject("season")
            val status = e.optJSONObject("status")?.optString("type", "") ?: ""
            val finished = status.equals("finished", true)
            out += Event(
                id = e.optInt("id", 0), home = home, away = away,
                homeId = homeObj.optInt("id", 0), awayId = awayObj.optInt("id", 0),
                homeGoals = if (finished) scoreValue(e.optJSONObject("homeScore")) else null,
                awayGoals = if (finished) scoreValue(e.optJSONObject("awayScore")) else null,
                uniqueTournamentId = uniqueTournament?.optInt("id", 0)?.takeIf { it > 0 },
                tournamentName = uniqueTournament?.optString("name", "")?.takeIf { it.isNotBlank() }
                    ?: tournament?.optString("name", "")?.takeIf { it.isNotBlank() },
                seasonId = seasonObj?.optInt("id", 0)?.takeIf { it > 0 }
            )
        }
    }

    private fun loadStandings(uniqueTournamentId: Int, seasonId: Int): List<Pair<String, Int>> {
        val json = runCatching { JSONObject(fetch("$base/unique-tournament/$uniqueTournamentId/season/$seasonId/standings/total")) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        val standings = json.optJSONArray("standings") ?: return emptyList()
        for (i in 0 until standings.length()) {
            val rows = standings.optJSONObject(i)?.optJSONArray("rows") ?: continue
            for (j in 0 until rows.length()) {
                val row = rows.optJSONObject(j) ?: continue
                val team = row.optJSONObject("team")?.optString("name", "")?.trim().orEmpty()
                val pos = row.optInt("position", 0)
                if (team.isNotBlank() && pos > 0) out += team to pos
            }
        }
        return out
    }

    private fun findRank(team: String, rows: List<Pair<String, Int>>): Int? = rows
        .filter { categoryCompatible(team, it.first) }
        .maxByOrNull { similarity(canonical(team), canonical(it.first)) }
        ?.takeIf { similarity(canonical(team), canonical(it.first)) >= 0.52 }
        ?.second

    private fun scoreValue(obj: JSONObject?): Int? {
        if (obj == null) return null
        for (key in listOf("normaltime", "current", "display")) if (obj.has(key) && !obj.isNull(key)) return obj.optInt(key)
        return null
    }

    private fun pointsLastFive(team: String, events: List<Event>): Int? {
        val points = mutableListOf<Int>()
        for (e in events) {
            val hg = e.homeGoals ?: continue
            val ag = e.awayGoals ?: continue
            val isHome = teamMatches(team, e.home)
            val isAway = teamMatches(team, e.away)
            if (!isHome && !isAway) continue
            val gf = if (isHome) hg else ag
            val ga = if (isHome) ag else hg
            points += when { gf > ga -> 3; gf == ga -> 1; else -> 0 }
            if (points.size == 5) break
        }
        return points.takeIf { it.size >= 3 }?.sum()
    }

    private fun homeWinRate(team: String, events: List<Event>): Double? {
        var games = 0; var wins = 0
        for (e in events) {
            val hg = e.homeGoals ?: continue
            val ag = e.awayGoals ?: continue
            if (!teamMatches(team, e.home)) continue
            games++; if (hg > ag) wins++
        }
        return if (games > 0) wins.toDouble() / games else null
    }

    private fun awayLossRate(team: String, events: List<Event>): Double? {
        var games = 0; var losses = 0
        for (e in events) {
            val hg = e.homeGoals ?: continue
            val ag = e.awayGoals ?: continue
            if (!teamMatches(team, e.away)) continue
            games++; if (ag < hg) losses++
        }
        return if (games > 0) losses.toDouble() / games else null
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OneStakeSelectionAI/1.7")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("SofaScore HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun youthCategory(name: String): String? = Regex("(?i)\\bU(1[5-9]|2[0-3])\\b").find(name)?.value?.uppercase(Locale.ROOT)
    private fun categoryCompatible(expected: String, actual: String): Boolean = youthCategory(expected) == youthCategory(actual)
    private fun teamMatches(expected: String, actual: String): Boolean = categoryCompatible(expected, actual) && similarity(canonical(expected), canonical(actual)) >= 0.52

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a); val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.94
        val xa = x.split(' ').filter { it.length > 1 }.toSet(); val ya = y.split(' ').filter { it.length > 1 }.toSet()
        val token = if (xa.isEmpty() || ya.isEmpty()) 0.0 else xa.intersect(ya).size.toDouble() / max(xa.size, ya.size)
        val edit = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length).coerceAtLeast(1)
        return (token * 0.7 + edit * 0.3).coerceIn(0.0, 1.0)
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(fc|cf|sc|ac|afc|club|de|the)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    private fun merge(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }
}
