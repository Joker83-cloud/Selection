package com.onestake.selectionai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val SEL_PREFIX = "[SEL:"

fun MatchCandidate.selectedMarket(): String {
    val start = notes.indexOf(SEL_PREFIX)
    if (start < 0) return "1"
    val end = notes.indexOf(']', start)
    if (end < 0) return "1"
    return notes.substring(start + SEL_PREFIX.length, end).takeIf { it == "1" || it == "X" || it == "2" } ?: "1"
}

fun MatchCandidate.selectedOdd(): Double = when (selectedMarket()) {
    "X" -> oddX
    "2" -> odd2
    else -> odd1
}

private data class MarketEval(
    val market: String,
    val odd: Double,
    val marketP: Double,
    val p: Double,
    val ev: Double,
    val edge: Double,
    val score: Int,
    val verdict: Verdict,
    val reason: String
)

object ScoringEngine {
    fun score(m: MatchCandidate, minOdd: Double = 1.50, maxOdd: Double = 2.50): MatchCandidate {
        if (m.odd1 <= 1.0 || m.oddX <= 1.0 || m.odd2 <= 1.0) {
            return m.copy(score = 0, estimatedP = 0.0, fairOdd = 0.0, ev = 0.0, verdict = Verdict.NEEDS_DATA,
                notes = merge("⚠️ DATI INSUFFICIENTI · quote 1-X-2 non valide", m.notes))
        }

        val overround = (1.0 / m.odd1) + (1.0 / m.oddX) + (1.0 / m.odd2)
        val marketP1 = (1.0 / m.odd1) / overround
        val marketPX = (1.0 / m.oddX) / overround
        val marketP2 = (1.0 / m.odd2) / overround

        val missing = mutableListOf<String>()
        if (m.fixtureId == null || m.leagueId == null || m.homeTeamId == null || m.awayTeamId == null) missing += "identificazione partita"
        if (m.homeRank == null || m.awayRank == null) missing += "classifica"
        if (m.homeHomeWinRate == null) missing += "rendimento casa"
        if (m.awayAwayLossRate == null) missing += "rendimento trasferta"
        if (m.homeFormPoints5 == null || m.awayFormPoints5 == null) missing += "ultime 5"

        if (missing.isNotEmpty()) {
            val bestMarket = listOf(
                Triple("1", m.odd1, marketP1), Triple("X", m.oddX, marketPX), Triple("2", m.odd2, marketP2)
            ).filter { it.second in minOdd..maxOdd }.maxByOrNull { it.third * it.second }
            val chosen = bestMarket ?: Triple("1", m.odd1, marketP1)
            val detail = "⚠️ DATI INSUFFICIENTI · mancanti: ${missing.distinct().joinToString(", ")}"
            return m.copy(
                score = 0,
                estimatedP = chosen.third,
                fairOdd = if (chosen.third > 0.0) 1.0 / chosen.third else 0.0,
                ev = 0.0,
                verdict = Verdict.NEEDS_DATA,
                notes = marker(chosen.first) + merge(detail, m.notes)
            )
        }

        val eligibleMarkets = listOf("1" to m.odd1, "X" to m.oddX, "2" to m.odd2).filter { it.second in minOdd..maxOdd }
        if (eligibleMarkets.isEmpty()) {
            return m.copy(
                score = 0,
                estimatedP = marketP1,
                fairOdd = 1.0 / marketP1,
                ev = 0.0,
                verdict = Verdict.PASS,
                notes = marker("1") + "Nessuna quota 1-X-2 nel filtro ${fmt(minOdd)}–${fmt(maxOdd)}"
            )
        }

        // Filtro prudenziale: una lavagna oltre l'8% non viene trattata come mercato giocabile.
        if (overround > 1.08) {
            val best = eligibleMarkets.maxByOrNull { (_, odd) ->
                val p = when (odd) { m.oddX -> marketPX; m.odd2 -> marketP2; else -> marketP1 }
                p * odd
            } ?: ("1" to m.odd1)
            val p = when (best.first) { "X" -> marketPX; "2" -> marketP2; else -> marketP1 }
            return m.copy(score = 0, estimatedP = p, fairOdd = 1.0 / p, ev = 0.0, verdict = Verdict.PASS,
                notes = marker(best.first) + "PASS · overround ${(overround * 100 - 100).fmt1Local()}% > 8%")
        }

        val rankGap = m.awayRank!! - m.homeRank!!
        val formDiff = m.homeFormPoints5!! - m.awayFormPoints5!!

        val rankAdj = when {
            rankGap >= 8 -> .040
            rankGap >= 4 -> .028
            rankGap >= 1 -> .014
            rankGap <= -8 -> -.040
            rankGap <= -4 -> -.028
            rankGap < 0 -> -.014
            else -> 0.0
        }
        val formAdj = formDiff.coerceIn(-10, 10) * .0030
        val homeAdj = ((m.homeHomeWinRate!! - .50) * .090).coerceIn(-.035, .035)
        val awayFragAdj = ((m.awayAwayLossRate!! - .45) * .080).coerceIn(-.030, .030)
        val directional = (rankAdj + formAdj + homeAdj + awayFragAdj).coerceIn(-.090, .090)

        // Il pareggio riceve solo aggiustamenti modesti: senza statistiche draw-specifiche il mercato resta l'ancora principale.
        val closeness = (if (abs(rankGap) <= 2) .016 else if (abs(rankGap) <= 4) .008 else -.006) +
            (if (abs(formDiff) <= 2) .014 else if (abs(formDiff) <= 4) .006 else -.005)

        var p1Raw = (marketP1 + directional).coerceIn(.08, .82)
        var p2Raw = (marketP2 - directional * .88).coerceIn(.08, .75)
        var pxRaw = (marketPX + closeness - abs(directional) * .10).coerceIn(.10, .45)
        val total = p1Raw + pxRaw + p2Raw
        p1Raw /= total; pxRaw /= total; p2Raw /= total

        fun evaluate(market: String, odd: Double, marketP: Double, p: Double): MarketEval {
            val breakEven = 1.0 / odd
            val ev = p * odd - 1.0
            val edge = p - breakEven
            val context = when (market) {
                "1" -> ((directional * 240.0) + (m.homeHomeWinRate - .50) * 18.0).toInt()
                "2" -> ((-directional * 240.0) + ((1.0 - m.awayAwayLossRate) - .50) * 12.0).toInt()
                else -> (closeness * 300.0 - abs(directional) * 80.0).toInt()
            }
            val score = (58.0 + ev * 220.0 + edge * 260.0 + context).toInt().coerceIn(0, 100)
            val verdict = when {
                score >= 80 && ev >= .06 && edge >= .025 -> Verdict.STRONG
                score >= 70 && ev >= .03 && edge >= .015 -> Verdict.PRUDENT
                else -> Verdict.PASS
            }
            val reason = when (market) {
                "1" -> when {
                    directional >= .05 -> "vantaggio casa supportato da classifica/forma/casa"
                    directional >= .015 -> "profilo casa moderatamente favorevole"
                    else -> "quota 1 valutata soprattutto contro il mercato"
                }
                "2" -> when {
                    directional <= -.05 -> "vantaggio ospite supportato da classifica/forma"
                    directional <= -.015 -> "profilo ospite moderatamente favorevole"
                    else -> "quota 2 valutata soprattutto contro il mercato"
                }
                else -> if (closeness >= .02) "squadre vicine per classifica e forma" else "pareggio ancorato alla probabilità di mercato"
            }
            return MarketEval(market, odd, marketP, p, ev, edge, score, verdict, reason)
        }

        val all = listOf(
            evaluate("1", m.odd1, marketP1, p1Raw),
            evaluate("X", m.oddX, marketPX, pxRaw),
            evaluate("2", m.odd2, marketP2, p2Raw)
        ).filter { it.odd in minOdd..maxOdd }

        // Prima il verdetto, poi EV e infine score: non scegliamo automaticamente la quota più bassa.
        val chosen = all.maxWithOrNull(compareBy<MarketEval>({ verdictRank(it.verdict) }, { it.ev }, { it.score }))!!
        val reasons = mutableListOf<String>()
        reasons += "Mercato migliore ${chosen.market} @${fmt(chosen.odd)}"
        reasons += chosen.reason
        reasons += "P mercato ${(chosen.marketP * 100).fmt1Local()}%"
        if (chosen.verdict == Verdict.PASS && chosen.ev < 0.0) reasons += "nessun valore positivo"
        if (chosen.verdict == Verdict.PASS && chosen.ev >= 0.0) reasons += "edge positivo ma sotto soglia prudenziale"
        reasons += "forma pesata ${m.homeFormPoints5}/${m.awayFormPoints5}"

        return m.copy(
            score = chosen.score,
            estimatedP = chosen.p,
            fairOdd = if (chosen.p > 0.0) 1.0 / chosen.p else 0.0,
            ev = chosen.ev,
            verdict = chosen.verdict,
            notes = marker(chosen.market) + merge(reasons.distinct().joinToString(" · "), stripMarker(m.notes))
        )
    }

    private fun verdictRank(v: Verdict): Int = when (v) {
        Verdict.STRONG -> 3
        Verdict.PRUDENT -> 2
        Verdict.PASS -> 1
        else -> 0
    }

    private fun marker(market: String) = "[SEL:$market] "
    private fun stripMarker(s: String): String = s.replace(Regex("\\[SEL:(1|X|2)]\\s*"), "").trim()
    private fun merge(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> "$a · $b"
    }
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}

private fun Double.fmt1Local() = String.format(java.util.Locale.US, "%.1f", this)
