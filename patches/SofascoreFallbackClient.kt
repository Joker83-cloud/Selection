package com.onestake.selectionai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Secondary data source used only when Diretta has already identified the match/league
 * but cannot provide recent form or home/away performance.
 */
class SofascoreFallbackClient {
    private val base = "https://www.sofascore.com/api/v1"

    private data class TeamHit(val id: Int, val name: String)
    private data class Event(
        val home: String,
        val away: String,
        val homeGoals: Int,
        val awayGoals: Int
    )

    fun enrich(candidate: MatchCandidate): MatchCandidate {
        // Do not use the secondary source to invent a fixture that Diretta did not identify.
        if (candidate.leagueName.isNullOrBlank() || candidate.fixtureId == null) return candidate

        var homePts = candidate.homeFormPoints5
        var awayPts = candidate.awayFormPoints5
        var homeRate = candidate.homeHomeWinRate
        var awayLossRate = candidate.awayAwayLossRate

        val needHome = homePts == null || homeRate == null
        val needAway = awayPts == null || awayLossRate == null
        if (!needHome && !needAway) return candidate

        val recovered = mutableListOf<String>()

        if (needHome) {
            findTeam(candidate.home)?.let { team ->
                val events = loadLastEvents(team.id)
                if (homePts == null) {
                    pointsLastFive(candidate.home, events)?.let {
                        homePts = it
                        recovered += "forma casa"
                    }
                }
                if (homeRate == null) {
                    homeWinRate(candidate.home, events)?.let {
                        homeRate = it
                        recovered += "rendimento casa"
                    }
                }
            }
        }

        if (needAway) {
            findTeam(candidate.away)?.let { team ->
                val events = loadLastEvents(team.id)
                if (awayPts == null) {
                    pointsLastFive(candidate.away, events)?.let {
                        awayPts = it
                        recovered += "forma ospite"
                    }
                }
                if (awayLossRate == null) {
                    awayLossRate(candidate.away, events)?.let {
                        awayLossRate = it
                        recovered += "rendimento trasferta"
                    }
                }
            }
        }

        if (recovered.isEmpty()) return candidate.copy(
            notes = merge(candidate.notes, "Fallback SofaScore: nessun dato aggiuntivo recuperato")
        )

        return candidate.copy(
            homeFormPoints5 = homePts,
            awayFormPoints5 = awayPts,
            homeHomeWinRate = homeRate,
            awayAwayLossRate = awayLossRate,
            notes = merge(candidate.notes, "Fallback SofaScore · recuperati: ${recovered.distinct().joinToString(", ")}")
        )
    }

    private fun findTeam(expected: String): TeamHit? {
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
            if (score > bestScore) {
                bestScore = score
                best = TeamHit(id, name)
            }
        }
        return best?.takeIf { bestScore >= 0.68 }
    }

    private fun loadLastEvents(teamId: Int): List<Event> {
        val out = mutableListOf<Event>()
        for (page in 0..1) {
            val json = runCatching { JSONObject(fetch("$base/team/$teamId/events/last/$page")) }.getOrNull() ?: continue
            val arr = json.optJSONArray("events") ?: continue
            parseEvents(arr, out)
            if (out.size >= 12 || !json.optBoolean("hasNextPage", false)) break
        }
        return out
    }

    private fun parseEvents(arr: JSONArray, out: MutableList<Event>) {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val status = e.optJSONObject("status")?.optString("type", "") ?: ""
            if (!status.equals("finished", true)) continue
            val home = e.optJSONObject("homeTeam")?.optString("name", "")?.trim().orEmpty()
            val away = e.optJSONObject("awayTeam")?.optString("name", "")?.trim().orEmpty()
            val hs = e.optJSONObject("homeScore")
            val ascore = e.optJSONObject("awayScore")
            val hg = scoreValue(hs)
            val ag = scoreValue(ascore)
            if (home.isNotBlank() && away.isNotBlank() && hg != null && ag != null) {
                out += Event(home, away, hg, ag)
            }
        }
    }

    private fun scoreValue(obj: JSONObject?): Int? {
        if (obj == null) return null
        for (key in listOf("normaltime", "current", "display")) {
            if (obj.has(key) && !obj.isNull(key)) return obj.optInt(key)
        }
        return null
    }

    private fun pointsLastFive(team: String, events: List<Event>): Int? {
        val points = mutableListOf<Int>()
        for (e in events) {
            val isHome = teamMatches(team, e.home)
            val isAway = teamMatches(team, e.away)
            if (!isHome && !isAway) continue
            val gf = if (isHome) e.homeGoals else e.awayGoals
            val ga = if (isHome) e.awayGoals else e.homeGoals
            points += when {
                gf > ga -> 3
                gf == ga -> 1
                else -> 0
            }
            if (points.size == 5) break
        }
        return points.takeIf { it.size >= 3 }?.sum()
    }

    private fun homeWinRate(team: String, events: List<Event>): Double? {
        var games = 0
        var wins = 0
        for (e in events) {
            if (!teamMatches(team, e.home)) continue
            games++
            if (e.homeGoals > e.awayGoals) wins++
        }
        return if (games > 0) wins.toDouble() / games else null
    }

    private fun awayLossRate(team: String, events: List<Event>): Double? {
        var games = 0
        var losses = 0
        for (e in events) {
            if (!teamMatches(team, e.away)) continue
            games++
            if (e.awayGoals < e.homeGoals) losses++
        }
        return if (games > 0) losses.toDouble() / games else null
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OneStakeSelectionAI/1.3")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("SofaScore HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun youthCategory(name: String): String? =
        Regex("(?i)\\bU(1[5-9]|2[0-3])\\b").find(name)?.value?.uppercase(Locale.ROOT)

    private fun categoryCompatible(expected: String, actual: String): Boolean =
        youthCategory(expected) == youthCategory(actual)

    private fun teamMatches(expected: String, actual: String): Boolean =
        categoryCompatible(expected, actual) && similarity(expected, actual) >= 0.68

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.94
        val xa = x.split(' ').filter { it.length > 1 }.toSet()
        val ya = y.split(' ').filter { it.length > 1 }.toSet()
        val token = if (xa.isEmpty() || ya.isEmpty()) 0.0 else xa.intersect(ya).size.toDouble() / max(xa.size, ya.size)
        val edit = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length).coerceAtLeast(1)
        return (token * 0.7 + edit * 0.3).coerceIn(0.0, 1.0)
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(fc|cf|sc|ac|afc|club|de|the)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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
            val tmp = prev
            prev = cur
            cur = tmp
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
