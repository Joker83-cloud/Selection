package com.onestake.selectionai

object OcrParser {
    private val oddRegex = Regex("(?<!\\d)([1-9]\\d?[.,]\\d{1,2})(?!\\d)")
    private val timeRegex = Regex("(?i)^(oggi|domani)?\\s*-?\\s*\\d{1,2}[:.]\\d{2}$|^\\d{1,2}[:.]\\d{2}$")
    private val marketLabelRegex = Regex("(?i)^(1|x|2)$")
    private val plusRegex = Regex("^\\+\\s*\\d*$")
    private val leagueRegex = Regex("^[A-Z]{3}\\s+.*")

    fun parse(text: String): List<MatchCandidate> {
        val lines = text.lines()
            .map { normalize(it) }
            .filter { it.isNotBlank() }

        val out = mutableListOf<MatchCandidate>()

        for (start in lines.indices) {
            val foundOdds = mutableListOf<Pair<Int, Double>>()
            var end = start
            while (end < lines.size && end <= start + 14 && foundOdds.size < 3) {
                val line = lines[end]
                val vals = oddRegex.findAll(line).mapNotNull { m ->
                    m.groupValues[1].replace(',', '.').toDoubleOrNull()
                }.filter { it in 1.01..25.0 }.toList()

                vals.forEach { foundOdds += end to it }
                end++
            }

            if (foundOdds.size < 3) continue

            val odds = foundOdds.take(3).map { it.second }
            val firstOddLine = foundOdds.first().first
            val nearbyStart = (firstOddLine - 5).coerceAtLeast(0)
            val nearbyEnd = (foundOdds[2].first + 2).coerceAtMost(lines.lastIndex)
            val nearby = lines.subList(nearbyStart, nearbyEnd + 1)
            val labels = nearby.filter { marketLabelRegex.matches(it.lowercase()) }
                .map { it.lowercase() }.toSet()
            val compact = foundOdds[2].first - firstOddLine <= 8
            if (!(compact || labels.containsAll(setOf("1", "x", "2")))) continue

            val names = mutableListOf<String>()
            var j = firstOddLine - 1
            while (j >= 0 && j >= firstOddLine - 12 && names.size < 2) {
                val s = lines[j]
                if (isTeamCandidate(s)) names += s
                j--
            }

            if (names.size == 2) {
                val away = cleanTeam(names[0])
                val home = cleanTeam(names[1])
                if (home.isNotBlank() && away.isNotBlank() && home.lowercase() != away.lowercase()) {
                    out += MatchCandidate(
                        home = home,
                        away = away,
                        odd1 = odds[0],
                        oddX = odds[1],
                        odd2 = odds[2]
                    )
                }
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
        if (plusRegex.matches(s)) return false
        if (s.startsWith("+")) return false
        if (leagueRegex.matches(s)) return false
        if (s.contains('%')) return false
        if (s.equals("oggi", true) || s.equals("domani", true)) return false
        return s.any { it.isLetter() }
    }

    private fun normalize(s: String): String = s
        .trim()
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("\\s+"), " ")

    private fun cleanTeam(s: String): String = s
        .replace(Regex("^[^A-Za-zÀ-ÿ0-9]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
