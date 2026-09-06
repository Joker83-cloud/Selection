package com.onestake.selectionai

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

object OcrParser {
    private val oddRegex = Regex("(?<!\\d)([1-9]\\d?[.,]\\d{1,2})(?!\\d)")
    private val timeRegex = Regex("(?i)^(oggi|domani)?\\s*-?\\s*\\d{1,2}[:.]\\d{2}$|^\\d{1,2}[:.]\\d{2}$")
    private val marketLabelRegex = Regex("(?i)^(1|x|2)$")
    private val plusRegex = Regex("^\\+\\s*\\d*$")
    private val leagueRegex = Regex("^[A-Z]{3}\\s+.*")

    private data class Token(val text: String, val cx: Int, val cy: Int, val h: Int, val left: Int, val right: Int)

    fun parse(result: Text): List<MatchCandidate> {
        val tokens = result.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { el ->
                    val b = el.boundingBox ?: return@mapNotNull null
                    Token(normalize(el.text), b.centerX(), b.centerY(), b.height(), b.left, b.right)
                }
            }
        }.filter { it.text.isNotBlank() }

        if (tokens.isEmpty()) return parse(result.text)

        val oddTokens = tokens.mapNotNull { t ->
            val m = oddRegex.find(t.text) ?: return@mapNotNull null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (v !in 1.01..25.0 || timeRegex.matches(t.text)) return@mapNotNull null
            t to v
        }

        if (oddTokens.size < 3) return parse(result.text)

        val medianH = tokens.map { it.h }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 24 else it[it.size / 2] }
        val yTolerance = (medianH * 1.8).toInt().coerceAtLeast(20)
        val rows = mutableListOf<MutableList<Pair<Token, Double>>>()

        oddTokens.sortedBy { it.first.cy }.forEach { item ->
            val row = rows.minByOrNull { r -> abs(r.map { it.first.cy }.average() - item.first.cy) }
            if (row != null && abs(row.map { it.first.cy }.average() - item.first.cy) <= yTolerance) row += item
            else rows += mutableListOf(item)
        }

        val out = mutableListOf<MatchCandidate>()

        rows.forEach { row ->
            val sortedOdds = row.sortedBy { it.first.cx }
            if (sortedOdds.size < 3) return@forEach

            for (i in 0..sortedOdds.size - 3) {
                val triple = sortedOdds.subList(i, i + 3)
                val xs = triple.map { it.first.cx }
                if (!(xs[0] < xs[1] && xs[1] < xs[2])) continue
                val rowY = triple.map { it.first.cy }.average()
                val firstOddX = triple.first().first.left

                val teamCandidates = tokens.filter { t ->
                    t.right < firstOddX &&
                    abs(t.cy - rowY) <= medianH * 6 &&
                    isTeamCandidate(t.text)
                }.sortedBy { it.cy }

                if (teamCandidates.size < 2) continue

                val nearby = teamCandidates.filter { it.cy <= rowY + medianH * 2 }
                val chosen = (if (nearby.size >= 2) nearby else teamCandidates).takeLast(2)
                if (chosen.size < 2) continue

                val home = cleanTeam(chosen[0].text)
                val away = cleanTeam(chosen[1].text)
                if (home.isBlank() || away.isBlank() || home.equals(away, true)) continue

                out += MatchCandidate(
                    home = home,
                    away = away,
                    odd1 = triple[0].second,
                    oddX = triple[1].second,
                    odd2 = triple[2].second
                )
                break
            }
        }

        val clean = out.distinctBy { "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}" }
        return if (clean.isNotEmpty()) clean else parse(result.text)
    }

    fun parse(text: String): List<MatchCandidate> {
        val lines = text.lines().map { normalize(it) }.filter { it.isNotBlank() }
        val out = mutableListOf<MatchCandidate>()
        val timeIndexes = lines.indices.filter { timeRegex.matches(lines[it]) }

        if (timeIndexes.isNotEmpty()) {
            timeIndexes.forEachIndexed { pos, ti ->
                val prevTime = if (pos == 0) -1 else timeIndexes[pos - 1]
                val nextTime = if (pos == timeIndexes.lastIndex) lines.size else timeIndexes[pos + 1]

                val before = lines.subList((prevTime + 1).coerceAtLeast(0), ti)
                val names = before.filter { isTeamCandidate(it) }.takeLast(2)
                if (names.size < 2) return@forEachIndexed

                val searchStart = (ti - 4).coerceAtLeast(prevTime + 1)
                val searchEnd = nextTime
                val odds = lines.subList(searchStart, searchEnd)
                    .filterNot { timeRegex.matches(it) }
                    .flatMap { line -> oddRegex.findAll(line).mapNotNull { m -> m.groupValues[1].replace(',', '.').toDoubleOrNull() }.toList() }
                    .filter { it in 1.01..25.0 }
                    .take(3)

                if (odds.size == 3) {
                    out += MatchCandidate(cleanTeam(names[0]), cleanTeam(names[1]), odds[0], odds[1], odds[2])
                }
            }
        }

        return out.distinctBy { "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}" }
    }

    private fun isTeamCandidate(s: String): Boolean {
        if (s.length < 2) return false
        if (oddRegex.containsMatchIn(s)) return false
        if (timeRegex.matches(s)) return false
        if (marketLabelRegex.matches(s.lowercase())) return false
        if (plusRegex.matches(s)) return false
        if (s.startsWith("+")) return false
        if (leagueRegex.matches(s)) return false
        if (s.contains('%')) return false
        if (s.equals("oggi", true) || s.equals("domani", true)) return false
        if (s.equals("calcio", true)) return false
        return s.any { it.isLetter() }
    }

    private fun normalize(s: String): String = s.trim().replace('–', '-').replace('—', '-').replace(Regex("\\s+"), " ")
    private fun cleanTeam(s: String): String = s.replace(Regex("^[^A-Za-zÀ-ÿ0-9]+"), "").replace(Regex("\\s+"), " ").trim()
}
