package com.onestake.selectionai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Trasforma le ultime partite in una forma PESATA 0..15.
 * Una vittoria non vale sempre 3 punti: pesano forza dell'avversario,
 * casa/trasferta e margine del risultato. Una sconfitta contro una squadra
 * forte pesa meno di una sconfitta interna contro una squadra debole.
 */
object WeightedHistoryEngine {
    private const val BASE = "https://www.sofascore.com/api/v1"

    private data class PastEvent(
        val homeId: Int,
        val awayId: Int,
        val homeName: String,
        val awayName: String,
        val homeGoals: Int,
        val awayGoals: Int,
        val tournamentId: Int?,
        val seasonId: Int?
    )

    private data class Table(val ranks: Map<Int, Int>, val size: Int)

    fun enrich(m: MatchCandidate): MatchCandidate {
        val homeId = m.homeTeamId ?: return m
        val awayId = m.awayTeamId ?: return m
        return runCatching {
            val tableCache = mutableMapOf<String, Table?>()
            val homeWeighted = weightedLastFive(homeId, tableCache)
            val awayWeighted = weightedLastFive(awayId, tableCache)
            if (homeWeighted == null && awayWeighted == null) return@runCatching m

            val h = homeWeighted ?: m.homeFormPoints5
            val a = awayWeighted ?: m.awayFormPoints5
            val note = if (h != null && a != null) {
                "Forma pesata qualità avversari: casa $h/15 · ospite $a/15"
            } else "Forma pesata parziale"

            m.copy(
                homeFormPoints5 = h,
                awayFormPoints5 = a,
                notes = merge(m.notes, note)
            )
        }.getOrElse { m.copy(notes = merge(m.notes, "Forma pesata non disponibile")) }
    }

    private fun weightedLastFive(teamId: Int, cache: MutableMap<String, Table?>): Int? {
        val events = loadLast(teamId).take(5)
        if (events.size < 3) return null
        var total = 0.0
        events.forEach { e ->
            val isHome = e.homeId == teamId
            val gf = if (isHome) e.homeGoals else e.awayGoals
            val ga = if (isHome) e.awayGoals else e.homeGoals
            val opponentId = if (isHome) e.awayId else e.homeId
            val table = if (e.tournamentId != null && e.seasonId != null) {
                val key = "${e.tournamentId}|${e.seasonId}"
                cache.getOrPut(key) { loadTable(e.tournamentId, e.seasonId) }
            } else null
            val oppRank = table?.ranks?.get(opponentId)
            val strength = opponentStrength(oppRank, table?.size ?: 0)
            val margin = gf - ga

            var value = when {
                margin > 0 -> 2.15
                margin == 0 -> 1.05
                else -> 0.30
            }

            // Avversario forte: premia vittorie/pareggi e attenua le sconfitte.
            if (margin > 0) value += (strength - 0.50) * 1.15
            else if (margin == 0) value += (strength - 0.50) * 0.70
            else value += (strength - 0.50) * 0.35

            // Vincere fuori è più difficile; perdere in casa contro una debole è peggio.
            if (!isHome && margin > 0) value += 0.30
            if (isHome && margin < 0) value -= 0.20

            // Intensità del risultato senza far dominare un singolo 5-0.
            if (margin >= 3) value += 0.35
            else if (margin == 2) value += 0.20
            if (margin <= -3) value -= 0.22
            else if (margin == -2) value -= 0.12

            total += value.coerceIn(0.0, 3.0)
        }

        // Riporta il campione disponibile su scala equivalente alle classiche ultime 5 (0..15).
        val normalized = total * (5.0 / events.size.toDouble())
        return normalized.roundToInt().coerceIn(0, 15)
    }

    private fun opponentStrength(rank: Int?, size: Int): Double {
        if (rank == null || size < 4) return 0.50
        // 1° = circa 1.0, ultimo = circa 0.0
        return (1.0 - (rank - 1).toDouble() / (size - 1).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun loadLast(teamId: Int): List<PastEvent> {
        val out = mutableListOf<PastEvent>()
        for (page in 0..1) {
            val json = JSONObject(fetch("$BASE/team/$teamId/events/last/$page"))
            val arr = json.optJSONArray("events") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (!e.optJSONObject("status")?.optString("type", "").equals("finished", true)) continue
                val h = e.optJSONObject("homeTeam") ?: continue
                val a = e.optJSONObject("awayTeam") ?: continue
                val hs = scoreValue(e.optJSONObject("homeScore")) ?: continue
                val as_ = scoreValue(e.optJSONObject("awayScore")) ?: continue
                val unique = e.optJSONObject("tournament")?.optJSONObject("uniqueTournament")
                val season = e.optJSONObject("season")
                out += PastEvent(
                    homeId = h.optInt("id", 0), awayId = a.optInt("id", 0),
                    homeName = h.optString("name", ""), awayName = a.optString("name", ""),
                    homeGoals = hs, awayGoals = as_,
                    tournamentId = unique?.optInt("id", 0)?.takeIf { it > 0 },
                    seasonId = season?.optInt("id", 0)?.takeIf { it > 0 }
                )
                if (out.size >= 5) return out
            }
            if (!json.optBoolean("hasNextPage", false)) break
        }
        return out
    }

    private fun loadTable(tournamentId: Int, seasonId: Int): Table? {
        return runCatching {
            val json = JSONObject(fetch("$BASE/unique-tournament/$tournamentId/season/$seasonId/standings/total"))
            val standings = json.optJSONArray("standings") ?: return@runCatching null
            val ranks = linkedMapOf<Int, Int>()
            for (i in 0 until standings.length()) {
                val rows = standings.optJSONObject(i)?.optJSONArray("rows") ?: continue
                for (j in 0 until rows.length()) {
                    val row = rows.optJSONObject(j) ?: continue
                    val id = row.optJSONObject("team")?.optInt("id", 0) ?: 0
                    val pos = row.optInt("position", 0)
                    if (id > 0 && pos > 0 && id !in ranks) ranks[id] = pos
                }
                if (ranks.isNotEmpty()) break
            }
            if (ranks.isEmpty()) null else Table(ranks, ranks.size)
        }.getOrNull()
    }

    private fun scoreValue(o: JSONObject?): Int? {
        if (o == null) return null
        val n = o.optInt("normaltime", Int.MIN_VALUE)
        if (n != Int.MIN_VALUE) return n
        val c = o.optInt("current", Int.MIN_VALUE)
        return c.takeIf { it != Int.MIN_VALUE }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6500
        c.readTimeout = 6500
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0 OneStake/1.0")
        c.setRequestProperty("Accept", "application/json")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun merge(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }
}
