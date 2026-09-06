package com.onestake.selectionai

import com.google.mlkit.vision.text.Text
import kotlin.math.abs

object OcrParser {
    private val oddRegex = Regex("(?<!\\d)([1-9]\\d?[.,]\\d{1,2})(?!\\d)")
    private val timeRegex = Regex("(?i)^(oggi|domani)?\\s*-?\\s*\\d{1,2}[:.]\\d{2}$|^\\d{1,2}[:.]\\d{2}$")
    private val marketLabelRegex = Regex("(?i)^(1|x|2)$")
    private val plusRegex = Regex("^\\+\\s*\\d*$")
    private val leagueRegex = Regex("^[A-Z]{3}\\s+.*")

    private data class Token(
        val text: String,
        val cx: Int,
        val cy: Int,
        val h: Int,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int
    )

    fun parse(result: Text): List<MatchCandidate> {
        val lineTokens = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                Token(normalize(line.text), b.centerX(), b.centerY(), b.height(), b.left, b.right, b.top, b.bottom)
            }
        }.filter { it.text.isNotBlank() }

        val elementTokens = result.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { el ->
                    val b = el.boundingBox ?: return@mapNotNull null
                    Token(normalize(el.text), b.centerX(), b.centerY(), b.height(), b.left, b.right, b.top, b.bottom)
                }
            }
        }.filter { it.text.isNotBlank() }

        if (lineTokens.isEmpty()) return parse(result.text)

        val timeLines = lineTokens.filter { timeRegex.matches(it.text) }.sortedBy { it.cy }
        val maxRight = lineTokens.maxOfOrNull { it.right } ?: 1000

        // Preferred parser for the bookmaker layout: every event card contains one time line,
        // two team-name lines on the left and three decimal odds in the right half.
        if (timeLines.isNotEmpty() && elementTokens.isNotEmpty()) {
            val out = mutableListOf<MatchCandidate>()

            timeLines.forEachIndexed { index, time ->
                val prevTimeY = timeLines.getOrNull(index - 1)?.cy
                val nextTimeY = timeLines.getOrNull(index + 1)?.cy
                val topBound = if (prevTimeY == null) 0 else (prevTimeY + time.cy) / 2
                val bottomBound = if (nextTimeY == null) Int.MAX_VALUE else (time.cy + nextTimeY) / 2

                val cardLines = lineTokens.filter { it.cy in topBound until bottomBound }
                val cardElements = elementTokens.filter { it.cy in topBound until bottomBound }

                val odds = cardElements.mapNotNull { t ->
                    val m = oddRegex.find(t.text) ?: return@mapNotNull null
                    val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                    if (value !in 1.01..25.0) return@mapNotNull null
                    t to value
                }
                    .filter { (t, _) -> t.cx > maxRight * 0.40 }
                    .sortedBy { it.first.cx }

                if (odds.size < 3) return@forEachIndexed

                // Keep one price per 1/X/2 horizontal column. If OCR duplicated a price,
                // collapse elements that are almost on top of each other.
                val distinctOdds = mutableListOf<Pair<Token, Double>>()
                odds.forEach { item ->
                    if (distinctOdds.none { abs(it.first.cx - item.first.cx) < 28 }) distinctOdds += item
                }
                if (distinctOdds.size < 3) return@forEachIndexed
                val triple = distinctOdds.take(3)
                val firstOddX = triple.first().first.cx

                val names = cardLines
                    .filter { isTeamCandidate(it.text) }
                    .filter { it.cy < time.cy }
                    .filter { it.cx < firstOddX }
                    .sortedBy { it.cy }

                if (names.size < 2) return@forEachIndexed

                // In each card the last two valid text lines before the time are Home and Away.
                val chosen = names.takeLast(2)
                val home = cleanTeam(chosen[0].text)
                val away = cleanTeam(chosen[1].text)
                if (home.isBlank() || away.isBlank() || home.equals(away, true)) return@forEachIndexed

                out += MatchCandidate(
                    home = home,
                    away = away,
                    odd1 = triple[0].second,
                    oddX = triple[1].second,
                    odd2 = triple[2].second
                )
            }

            val clean = out.distinctBy {
                "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}"
            }
            if (clean.isNotEmpty()) return clean
        }

        // Secondary spatial parser for screenshots where the time label is not detected.
        if (elementTokens.isNotEmpty()) {
            val oddTokens = elementTokens.mapNotNull { t ->
                val m = oddRegex.find(t.text) ?: return@mapNotNull null
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                if (v !in 1.01..25.0) return@mapNotNull null
                t to v
            }

            if (oddTokens.size >= 3) {
                val medianH = elementTokens.map { it.h }.filter { it > 0 }.sorted().let {
                    if (it.isEmpty()) 24 else it[it.size / 2]
                }
                val yTolerance = (medianH * 2.1).toInt().coerceAtLeast(24)
                val rows = mutableListOf<MutableList<Pair<Token, Double>>>()

                oddTokens.sortedBy { it.first.cy }.forEach { item ->
                    val row = rows.minByOrNull { r -> abs(r.map { it.first.cy }.average() - item.first.cy) }
                    if (row != null && abs(row.map { it.first.cy }.average() - item.first.cy) <= yTolerance) row += item
                    else rows += mutableListOf(item)
                }

                val out = mutableListOf<MatchCandidate>()
                rows.forEach { row ->
                    val sorted = row.sortedBy { it.first.cx }
                    if (sorted.size < 3) return@forEach
                    val triple = sorted.take(3)
                    val rowY = triple.map { it.first.cy }.average()
                    val firstOddX = triple.first().first.cx
                    val names = lineTokens
                        .filter { isTeamCandidate(it.text) }
                        .filter { it.cx < firstOddX && it.cy < rowY && it.cy > rowY - 220 }
                        .sortedBy { it.cy }
                        .takeLast(2)
                    if (names.size < 2) return@forEach

                    out += MatchCandidate(
                        home = cleanTeam(names[0].text),
                        away = cleanTeam(names[1].text),
                        odd1 = triple[0].second,
                        oddX = triple[1].second,
                        odd2 = triple[2].second
                    )
                }
                if (out.isNotEmpty()) return out.distinctBy {
                    "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}"
                }
            }
        }

        return parse(result.text)
    }

    fun parse(text: String): List<MatchCandidate> {
        val lines = text.lines().map { normalize(it) }.filter { it.isNotBlank() }
        val out = mutableListOf<MatchCandidate>()
        val timeIndexes = lines.indices.filter { timeRegex.matches(lines[it]) }

        timeIndexes.forEachIndexed { pos, ti ->
            val prevTime = if (pos == 0) -1 else timeIndexes[pos - 1]
            val nextTime = if (pos == timeIndexes.lastIndex) lines.size else timeIndexes[pos + 1]
            val card = lines.subList((prevTime + 1).coerceAtLeast(0), nextTime)

            val names = lines.subList((prevTime + 1).coerceAtLeast(0), ti)
                .filter { isTeamCandidate(it) }
                .takeLast(2)
            if (names.size < 2) return@forEachIndexed

            val odds = card
                .filterNot { timeRegex.matches(it) }
                .flatMap { line ->
                    oddRegex.findAll(line).mapNotNull { m ->
                        m.groupValues[1].replace(',', '.').toDoubleOrNull()
                    }.toList()
                }
                .filter { it in 1.01..25.0 }
                .take(3)

            if (odds.size == 3) {
                out += MatchCandidate(
                    home = cleanTeam(names[0]),
                    away = cleanTeam(names[1]),
                    odd1 = odds[0],
                    oddX = odds[1],
                    odd2 = odds[2]
                )
            }
        }

        return out.distinctBy {
            "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}"
        }
    }

    private fun isTeamCandidate(s: String): Boolean {
        if (s.length < 2) return false
        if (oddRegex.containsMatchIn(s)) return false
        if (timeRegex.matches(s)) return false
        if (marketLabelRegex.matches(s.lowercase())) return false
        if (plusRegex.matches(s) || s.startsWith("+")) return false
        if (leagueRegex.matches(s)) return false
        if (s.contains('%')) return false
        if (s.equals("oggi", true) || s.equals("domani", true) || s.equals("calcio", true)) return false
        return s.any { it.isLetter() }
    }

    private fun normalize(s: String): String = s.trim()
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("\\s+"), " ")

    private fun cleanTeam(s: String): String = s
        .replace(Regex("^[^A-Za-zÀ-ÿ0-9]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
