package com.onestake.selectionai

import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class DirettaMobileClient {
    private val base = "https://m.diretta.it"

    data class LeagueMatch(
        val leagueName: String,
        val standingsUrl: String?,
        val home: String,
        val away: String
    )

    data class TeamResult(
        val home: String,
        val away: String,
        val homeGoals: Int,
        val awayGoals: Int
    )

    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val today = fetchDay(0)
        val match = findLeagueMatch(today, candidate)
            ?: return candidate.copy(notes = mergeNote(candidate.notes, "Diretta: partita non identificata nella pagina mobile di oggi"))

        var homeRank: Int? = null
        var awayRank: Int? = null
        match.standingsUrl?.let { url ->
            runCatching { fetchAbsolute(url) }.getOrNull()?.let { standingsHtml ->
                val rows = parseStandings(standingsHtml)
                homeRank = findRank(rows, candidate.home)
                awayRank = findRank(rows, candidate.away)
            }
        }

        val recentHome = mutableListOf<Int>()
        val recentAway = mutableListOf<Int>()
        var homeGamesAtHome = 0
        var homeWinsAtHome = 0
        var awayGamesAway = 0
        var awayLossesAway = 0

        for (d in 1..45) {
            val html = runCatching { fetchDay(-d) }.getOrNull() ?: continue
            val results = parseFinishedResults(html)
            for (r in results) {
                val homeIsHome = similarity(candidate.home, r.home) >= 0.78
                val homeIsAway = similarity(candidate.home, r.away) >= 0.78
                val awayIsHome = similarity(candidate.away, r.home) >= 0.78
                val awayIsAway = similarity(candidate.away, r.away) >= 0.78

                if ((homeIsHome || homeIsAway) && recentHome.size < 5) {
                    val gf = if (homeIsHome) r.homeGoals else r.awayGoals
                    val ga = if (homeIsHome) r.awayGoals else r.homeGoals
                    recentHome += when {
                        gf > ga -> 3
                        gf == ga -> 1
                        else -> 0
                    }
                }
                if ((awayIsHome || awayIsAway) && recentAway.size < 5) {
                    val gf = if (awayIsHome) r.homeGoals else r.awayGoals
                    val ga = if (awayIsHome) r.awayGoals else r.homeGoals
                    recentAway += when {
                        gf > ga -> 3
                        gf == ga -> 1
                        else -> 0
                    }
                }

                if (homeIsHome) {
                    homeGamesAtHome++
                    if (r.homeGoals > r.awayGoals) homeWinsAtHome++
                }
                if (awayIsAway) {
                    awayGamesAway++
                    if (r.awayGoals < r.homeGoals) awayLossesAway++
                }
            }
            if (recentHome.size >= 5 && recentAway.size >= 5 && homeGamesAtHome >= 3 && awayGamesAway >= 3) break
        }

        val homePts5 = recentHome.take(5).takeIf { it.size >= 3 }?.sum()
        val awayPts5 = recentAway.take(5).takeIf { it.size >= 3 }?.sum()
        val homeRate = if (homeGamesAtHome > 0) homeWinsAtHome.toDouble() / homeGamesAtHome else null
        val awayLossRate = if (awayGamesAway > 0) awayLossesAway.toDouble() / awayGamesAway else null

        val missing = mutableListOf<String>()
        if (homeRank == null || awayRank == null) missing += "classifica"
        if (homeRate == null) missing += "rendimento casa"
        if (awayLossRate == null) missing += "rendimento trasferta"
        if (homePts5 == null || awayPts5 == null) missing += "ultime 5"

        val syntheticLeagueId = -kotlin.math.abs(match.leagueName.hashCode()).coerceAtLeast(1)
        val syntheticHomeId = -kotlin.math.abs(candidate.home.hashCode()).coerceAtLeast(1)
        val syntheticAwayId = -kotlin.math.abs(candidate.away.hashCode()).coerceAtLeast(1)
        val syntheticFixtureId = -kotlin.math.abs((candidate.home + "|" + candidate.away + "|" + date).hashCode()).coerceAtLeast(1)

        val note = if (missing.isEmpty()) {
            "Diretta mobile · ${match.leagueName} · classifica, ultime 5 e casa/trasferta recuperati"
        } else {
            "Diretta mobile · ${match.leagueName} · mancanti: ${missing.distinct().joinToString(", ")}"
        }

        return candidate.copy(
            fixtureId = syntheticFixtureId,
            leagueId = syntheticLeagueId,
            leagueName = match.leagueName,
            season = candidate.season ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
            homeTeamId = syntheticHomeId,
            awayTeamId = syntheticAwayId,
            homeRank = homeRank,
            awayRank = awayRank,
            homeFormPoints5 = homePts5,
            awayFormPoints5 = awayPts5,
            homeHomeWinRate = homeRate,
            awayAwayLossRate = awayLossRate,
            notes = mergeNote(candidate.notes, note)
        )
    }

    private fun fetchDay(offset: Int): String {
        val path = when {
            offset == 0 -> "/"
            else -> "/?d=$offset"
        }
        return cache.computeIfAbsent(path) { fetchAbsolute(base + it) }
    }

    private fun fetchAbsolute(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OneStakeSelectionAI/0.8")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("Diretta HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun findLeagueMatch(html: String, c: MatchCandidate): LeagueMatch? {
        val blockRegex = Regex("(?is)<h4[^>]*>(.*?)</h4>(.*?)(?=<h4|$)")
        var best: LeagueMatch? = null
        var bestScore = 0.0
        for (m in blockRegex.findAll(html)) {
            val headerHtml = m.groupValues[1]
            val bodyHtml = m.groupValues[2]
            val league = cleanText(headerHtml).replace("Classifiche", "").trim()
            val standingsHref = Regex("(?is)href=[\"']([^\"']+)[\"'][^>]*>\\s*Classifiche").find(headerHtml)?.groupValues?.get(1)
            val standingsUrl = standingsHref?.let { if (it.startsWith("http")) it else base + if (it.startsWith("/")) it else "/$it" }
            for (line in htmlToLines(bodyHtml)) {
                val fixture = parseFixtureLine(line) ?: continue
                val hs = similarity(c.home, fixture.first)
                val ascore = similarity(c.away, fixture.second)
                val score = (hs + ascore) / 2.0
                if (hs >= 0.70 && ascore >= 0.70 && score > bestScore) {
                    bestScore = score
                    best = LeagueMatch(league, standingsUrl, fixture.first, fixture.second)
                }
            }
        }
        return best?.takeIf { bestScore >= 0.74 }
    }

    private fun parseStandings(html: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val rowRegex = Regex("(?is)<tr[^>]*>(.*?)</tr>")
        val cellRegex = Regex("(?is)<t[dh][^>]*>(.*?)</t[dh]>")
        for (row in rowRegex.findAll(html)) {
            val cells = cellRegex.findAll(row.groupValues[1]).map { cleanText(it.groupValues[1]) }.toList()
            if (cells.size < 2) continue
            val rank = cells[0].replace(".", "").trim().toIntOrNull() ?: continue
            val team = cells[1].trim()
            if (team.isNotBlank()) out += team to rank
        }
        if (out.isNotEmpty()) return out

        val lineRegex = Regex("^(\\d+)\\.\\s+(.+?)\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+[:\\-]\\d+\\s+\\d+$")
        for (line in htmlToLines(html)) {
            val m = lineRegex.find(line) ?: continue
            out += m.groupValues[2].trim() to m.groupValues[1].toInt()
        }
        return out
    }

    private fun findRank(rows: List<Pair<String, Int>>, team: String): Int? =
        rows.maxByOrNull { similarity(team, it.first) }?.takeIf { similarity(team, it.first) >= 0.72 }?.second

    private fun parseFinishedResults(html: String): List<TeamResult> {
        val out = mutableListOf<TeamResult>()
        val scoreRegex = Regex("^(.*?\\S)\\s+-\\s+(.*?\\S)\\s+(\\d{1,2})-(\\d{1,2})(?:\\s.*)?$")
        for (raw in htmlToLines(html)) {
            var line = raw
                .replace("Image", " ")
                .replace(Regex("^\\d{1,2}:\\d{2}\\s+"), "")
                .replace(Regex("^(?:Intervallo|Finale|Posticipata|Sospesa)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\d{1,3}(?:\\+\\d+)?'\\s*"), "")
                .trim()
            val m = scoreRegex.find(line) ?: continue
            val h = m.groupValues[1].trim()
            val a = m.groupValues[2].trim()
            val hg = m.groupValues[3].toIntOrNull() ?: continue
            val ag = m.groupValues[4].toIntOrNull() ?: continue
            if (h.length >= 2 && a.length >= 2) out += TeamResult(h, a, hg, ag)
        }
        return out
    }

    private fun parseFixtureLine(raw: String): Pair<String, String>? {
        var line = raw
            .replace("Image", " ")
            .replace(Regex("^\\d{1,2}:\\d{2}\\s+"), "")
            .replace(Regex("^(?:Intervallo|Finale|Posticipata|Sospesa)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d{1,3}(?:\\+\\d+)?'\\s*"), "")
            .replace(Regex("\\s+\\d{1,2}-\\d{1,2}(?:\\s.*)?$"), "")
            .replace(Regex("\\s+-\\s*$"), "")
            .trim()
        val idx = line.indexOf(" - ")
        if (idx <= 0 || idx >= line.length - 3) return null
        val h = line.substring(0, idx).trim()
        val a = line.substring(idx + 3).trim()
        return if (h.length >= 2 && a.length >= 2) h to a else null
    }

    private fun htmlToLines(html: String): List<String> {
        val prepared = html
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(?:div|p|li|tr|h[1-6])>"), "\n")
        return prepared
            .split('\n')
            .map { cleanText(it) }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
    }

    private fun cleanText(s: String): String = decodeEntities(s.replace(Regex("(?is)<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

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
        return (token * 0.68 + edit * 0.32).coerceIn(0.0, 1.0)
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

    private fun mergeNote(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }

    companion object {
        private val cache = ConcurrentHashMap<String, String>()
    }
}
