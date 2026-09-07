package com.onestake.selectionai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class ApiFootballClient(private val apiKey: String) {
    private val base = "https://v3.football.api-sports.io"

    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        if (apiKey.isBlank()) return candidate.copy(notes = "API key mancante")

        val fixture = findFixtureRobust(candidate, date)
            ?: return candidate.copy(notes = "⚠️ DATI INSUFFICIENTI · partita non identificata con certezza via API-Football")

        val league = fixture.getJSONObject("league")
        val teams = fixture.getJSONObject("teams")
        val homeObj = teams.getJSONObject("home")
        val awayObj = teams.getJSONObject("away")
        val leagueId = league.getInt("id")
        val season = league.getInt("season")
        val homeId = homeObj.getInt("id")
        val awayId = awayObj.getInt("id")

        val standingsData = get("/standings?league=$leagueId&season=$season")
        val rows = flattenStandings(standingsData)

        var homeRank: Int? = null
        var awayRank: Int? = null
        var homeHomeWinRate: Double? = null
        var awayAwayLossRate: Double? = null

        for (row in rows) {
            val teamId = row.optJSONObject("team")?.optInt("id") ?: continue
            if (teamId == homeId) {
                homeRank = row.optInt("rank").takeIf { it > 0 }
                homeHomeWinRate = ratio(row.optJSONObject("home"), "win")
            }
            if (teamId == awayId) {
                awayRank = row.optInt("rank").takeIf { it > 0 }
                awayAwayLossRate = ratio(row.optJSONObject("away"), "lose")
            }
        }

        // Some competitions expose the team in a different standings group. Ask directly by team as fallback.
        if (homeRank == null) {
            findStandingRowForTeam(leagueId, season, homeId)?.let { row ->
                homeRank = row.optInt("rank").takeIf { it > 0 }
                if (homeHomeWinRate == null) homeHomeWinRate = ratio(row.optJSONObject("home"), "win")
            }
        }
        if (awayRank == null) {
            findStandingRowForTeam(leagueId, season, awayId)?.let { row ->
                awayRank = row.optInt("rank").takeIf { it > 0 }
                if (awayAwayLossRate == null) awayAwayLossRate = ratio(row.optJSONObject("away"), "lose")
            }
        }

        // Team statistics are a second source for home/away records when standings rows are incomplete.
        if (homeHomeWinRate == null) homeHomeWinRate = teamHomeWinRate(leagueId, season, homeId)
        if (awayAwayLossRate == null) awayAwayLossRate = teamAwayLossRate(leagueId, season, awayId)

        val homePts5 = recentPoints(homeId)
        val awayPts5 = recentPoints(awayId)

        val missing = mutableListOf<String>()
        if (homeRank == null || awayRank == null) missing += "classifica"
        if (homeHomeWinRate == null) missing += "rendimento casa"
        if (awayAwayLossRate == null) missing += "rendimento trasferta"
        if (homePts5 == null || awayPts5 == null) missing += "ultime 5"

        val status = if (missing.isEmpty()) {
            "Dati completi · ${league.optString("name")} · classifica e forma recuperate"
        } else {
            "⚠️ DATI INSUFFICIENTI · mancanti: ${missing.distinct().joinToString(", ")} · ${league.optString("name")}" 
        }

        return candidate.copy(
            fixtureId = fixture.getJSONObject("fixture").getInt("id"),
            leagueId = leagueId,
            leagueName = league.optString("name"),
            season = season,
            homeTeamId = homeId,
            awayTeamId = awayId,
            homeRank = homeRank,
            awayRank = awayRank,
            homeFormPoints5 = homePts5,
            awayFormPoints5 = awayPts5,
            homeHomeWinRate = homeHomeWinRate,
            awayAwayLossRate = awayAwayLossRate,
            notes = status
        )
    }

    private fun ratio(split: JSONObject?, resultKey: String): Double? {
        if (split == null) return null
        val played = split.optInt("played")
        val value = split.optInt(resultKey)
        return if (played > 0) value.toDouble() / played else null
    }

    private fun flattenStandings(data: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val response = data.optJSONArray("response") ?: return out
        for (i in 0 until response.length()) {
            val league = response.optJSONObject(i)?.optJSONObject("league") ?: continue
            val groups = league.optJSONArray("standings") ?: continue
            for (g in 0 until groups.length()) {
                val table = groups.optJSONArray(g) ?: continue
                for (r in 0 until table.length()) table.optJSONObject(r)?.let(out::add)
            }
        }
        return out
    }

    private fun findStandingRowForTeam(leagueId: Int, season: Int, teamId: Int): JSONObject? {
        return try {
            flattenStandings(get("/standings?league=$leagueId&season=$season&team=$teamId"))
                .firstOrNull { it.optJSONObject("team")?.optInt("id") == teamId }
        } catch (_: Exception) { null }
    }

    private fun teamHomeWinRate(leagueId: Int, season: Int, teamId: Int): Double? {
        return try {
            val stats = get("/teams/statistics?league=$leagueId&season=$season&team=$teamId")
                .optJSONObject("response") ?: return null
            val fixtures = stats.optJSONObject("fixtures") ?: return null
            val played = fixtures.optJSONObject("played")?.optInt("home") ?: 0
            val wins = fixtures.optJSONObject("wins")?.optInt("home") ?: 0
            if (played > 0) wins.toDouble() / played else null
        } catch (_: Exception) { null }
    }

    private fun teamAwayLossRate(leagueId: Int, season: Int, teamId: Int): Double? {
        return try {
            val stats = get("/teams/statistics?league=$leagueId&season=$season&team=$teamId")
                .optJSONObject("response") ?: return null
            val fixtures = stats.optJSONObject("fixtures") ?: return null
            val played = fixtures.optJSONObject("played")?.optInt("away") ?: 0
            val losses = fixtures.optJSONObject("loses")?.optInt("away") ?: 0
            if (played > 0) losses.toDouble() / played else null
        } catch (_: Exception) { null }
    }

    private fun recentPoints(teamId: Int): Int? {
        return try {
            val arr = get("/fixtures?team=$teamId&last=5&status=FT").optJSONArray("response") ?: return null
            if (arr.length() == 0) return null
            var pts = 0
            var counted = 0
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val teams = f.optJSONObject("teams") ?: continue
                val home = teams.optJSONObject("home") ?: continue
                val away = teams.optJSONObject("away") ?: continue
                val isHome = home.optInt("id") == teamId
                val meWinner = if (isHome) home.opt("winner") else away.opt("winner")
                val otherWinner = if (isHome) away.opt("winner") else home.opt("winner")
                pts += when {
                    meWinner == true -> 3
                    otherWinner == true -> 0
                    else -> 1
                }
                counted++
            }
            if (counted > 0) pts else null
        } catch (_: Exception) { null }
    }

    private fun findFixtureRobust(c: MatchCandidate, date: String): JSONObject? {
        findFixtureOnDate(c, date, 0.62)?.let { return it }

        // Time-zone/bookmaker day boundary fallback: only used if today's slate did not identify the match.
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsed = runCatching { sdf.parse(date) }.getOrNull() ?: return null
        for (delta in listOf(-1, 1)) {
            val cal = Calendar.getInstance().apply { time = parsed; add(Calendar.DAY_OF_MONTH, delta) }
            findFixtureOnDate(c, sdf.format(cal.time), 0.68)?.let { return it }
        }
        return null
    }

    private fun findFixtureOnDate(c: MatchCandidate, date: String, threshold: Double): JSONObject? {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val arr = get("/fixtures?date=$encodedDate").optJSONArray("response") ?: return null
        var best: JSONObject? = null
        var bestScore = 0.0
        var secondScore = 0.0

        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val teams = f.optJSONObject("teams") ?: continue
            val h = teams.optJSONObject("home")?.optString("name").orEmpty()
            val a = teams.optJSONObject("away")?.optString("name").orEmpty()
            val hs = similarity(c.home, h)
            val ascore = similarity(c.away, a)
            val score = hs * 0.5 + ascore * 0.5
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = f
            } else if (score > secondScore) secondScore = score
        }

        // Require both a good absolute match and separation from the second-best fixture.
        return if (bestScore >= threshold && (bestScore - secondScore >= 0.08 || bestScore >= 0.86)) best else null
    }

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.93

        val xa = x.split(' ').filter { it.length > 1 }.toSet()
        val ya = y.split(' ').filter { it.length > 1 }.toSet()
        val token = if (xa.isEmpty() || ya.isEmpty()) 0.0 else xa.intersect(ya).size.toDouble() / max(xa.size, ya.size)
        val edit = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length).coerceAtLeast(1)
        return (token * 0.62 + edit * 0.38).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
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

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(fc|cf|sc|ac|afc|club|de|the)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun get(path: String): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("x-apisports-key", apiKey)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("API HTTP $code: ${body.take(180)}")
            val json = JSONObject(body)
            val errors = json.opt("errors")
            if (errors is JSONObject && errors.length() > 0) throw IllegalStateException("API: ${errors.toString().take(180)}")
            json
        } finally {
            conn.disconnect()
        }
    }
}
