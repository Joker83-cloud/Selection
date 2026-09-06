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
        val right: Int
    )

    fun parse(result: Text): List<MatchCandidate> {
        // Full OCR lines are used for team names so multi-word clubs remain intact.
        val lineTokens = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val b = line.boundingBox ?: return@mapNotNull null
                Token(normalize(line.text), b.centerX(), b.centerY(), b.height(), b.left, b.right)
            }
        }.filter { it.text.isNotBlank() }

        // Individual OCR elements are used for odds because 1 / X / 2 and prices can share a line.
        val elementTokens = result.textBlocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.elements.mapNotNull { el ->
                    val b = el.boundingBox ?: return@mapNotNull null
                    Token(normalize(el.text), b.centerX(), b.centerY(), b.height(), b.left, b.right)
                }
            }
        }.filter { it.text.isNotBlank() }

        if (lineTokens.isEmpty() || elementTokens.isEmpty()) return parse(result.text)

        val oddTokens = elementTokens.mapNotNull { t ->
            val m = oddRegex.find(t.text) ?: return@mapNotNull null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (v !in 1.01..25.0 || timeRegex.matches(t.text)) return@mapNotNull null
            t to v
        }
        if (oddTokens.size < 3) return parse(result.text)

        val medianLineH = lineTokens.map { it.h }.filter { it > 0 }.sorted().let {
            if (it.isEmpty()) 28 else it[it.size / 2]
        }
        val medianElementH = elementTokens.map { it.h }.filter { it > 0 }.sorted().let {
            if (it.isEmpty()) 24 else it[it.size / 2]
        }

        // Each bookmaker card has the 3 prices on one horizontal band.
        val yTolerance = (medianElementH * 1.8).toInt().coerceAtLeast(20)
        val rows = mutableListOf<MutableList<Pair<Token, Double>>>()
        oddTokens.sortedBy { it.first.cy }.forEach { item ->
            val row = rows.minByOrNull { r -> abs(r.map { it.first.cy }.average() - item.first.cy) }
            if (row != null && abs(row.map { it.first.cy }.average() - item.first.cy) <= yTolerance) {
                row += item
            } else {
                rows += mutableListOf(item)
            }
        }

        val out = mutableListOf<MatchCandidate>()

        rows.forEach { row ->
            val sortedOdds = row.sortedBy { it.first.cx }
            if (sortedOdds.size < 3) return@forEach

            // Try consecutive triples from left to right and keep the first geometrically valid 1-X-2 row.
            for (i in 0..sortedOdds.size - 3) {
                val triple = sortedOdds.subList(i, i + 3)
                val xs = triple.map { it.first.cx }
                if (!(xs[0] < xs[1] && xs[1] < xs[2])) continue

                val rowY = triple.map { it.first.cy }.average()
                val firstOddLeft = triple.first().first.left

                // Team names are the two nearest valid full lines to the LEFT of the first odd.
                // The vertical band is deliberately narrow so another card cannot leak into this one.
                val candidates = lineTokens.filter { t ->
                    t.right < firstOddLeft &&
                    t.cy >= rowY - medianLineH * 4.5 &&
                    t.cy <= rowY + medianLineH * 1.5 &&
                    isTeamCandidate(t.text)
                }

                if (candidates.size < 2) continue

                val chosen = candidates
                    .sortedBy { abs(it.cy - rowY) }
                    .take(2)
                    .sortedBy { it.cy }

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

        val clean = out.distinctBy {
            "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}"
        }
        return if (clean.isNotEmpty()) clean else parse(result.text)
    }

    // Conservative text-only fallback for devices/screenshots where bounding boxes are incomplete.
    fun parse(text: String): List<MatchCandidate> {
        val lines = text.lines().map { normalize(it) }.filter { it.isNotBlank() }
        val out = mutableListOf<MatchCandidate>()
        val timeIndexes = lines.indices.filter { timeRegex.matches(lines[it]) }

        timeIndexes.forEachIndexed { pos, ti ->
            val prevTime = if (pos == 0) -1 else timeIndexes[pos - 1]
            val nextTime = if (pos == timeIndexes.lastIndex) lines.size else timeIndexes[pos + 1]

            val names = lines.subList((prevTime + 1).coerceAtLeast(0), ti)
                .filter { isTeamCandidate(it) }
                .takeLast(2)
            if (names.size < 2) return@forEachIndexed

            val searchStart = (ti - 5).coerceAtLeast(prevTime + 1)
            val odds = lines.subList(searchStart, nextTime)
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
