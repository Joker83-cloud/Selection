package com.onestake.selectionai

import kotlin.math.max
import kotlin.math.min

object ScoringEngine {
    fun score(m: MatchCandidate): MatchCandidate {
        val impliedRaw = 1.0 / m.odd1
        val overround = (1.0 / m.odd1) + (1.0 / m.oddX) + (1.0 / m.odd2)
        val marketP = impliedRaw / overround

        val missing = mutableListOf<String>()
        if (m.fixtureId == null || m.leagueId == null || m.homeTeamId == null || m.awayTeamId == null) missing += "identificazione partita"
        if (m.homeRank == null || m.awayRank == null) missing += "classifica"
        if (m.homeHomeWinRate == null) missing += "rendimento casa"
        if (m.awayAwayLossRate == null) missing += "rendimento trasferta"
        if (m.homeFormPoints5 == null || m.awayFormPoints5 == null) missing += "ultime 5"

        if (missing.isNotEmpty()) {
            val detail = "⚠️ DATI INSUFFICIENTI · mancanti: ${missing.distinct().joinToString(", ")}"
            return m.copy(
                score = 0,
                estimatedP = marketP,
                fairOdd = if (marketP > 0.0) 1.0 / marketP else 0.0,
                ev = 0.0,
                verdict = Verdict.NEEDS_DATA,
                notes = if (m.notes.isBlank()) detail else "$detail · ${m.notes}"
            )
        }

        var pts = 0
        val reasons = mutableListOf<String>()

        when (m.odd1) {
            in 1.50..1.90 -> { pts += 24; reasons += "quota target" }
            in 1.91..2.10 -> { pts += 18; reasons += "quota accettabile" }
            in 2.11..2.50 -> pts += 8
            else -> return m.copy(score = 0, estimatedP = marketP, fairOdd = 1 / marketP, ev = 0.0, verdict = Verdict.PASS, notes = "Quota 1 fuori range OneStake")
        }

        if (m.homeRank!! < m.awayRank!!) { pts += 18; reasons += "casa avanti in classifica" }
        else if (m.homeRank > m.awayRank) { pts += 6; reasons += "casa dietro: pattern rimbalzo da testare" }

        when {
            m.homeHomeWinRate!! >= .65 -> { pts += 18; reasons += "forte rendimento casa" }
            m.homeHomeWinRate >= .50 -> pts += 12
            m.homeHomeWinRate >= .35 -> pts += 5
        }

        when {
            m.awayAwayLossRate!! >= .60 -> { pts += 14; reasons += "ospite fragile fuori" }
            m.awayAwayLossRate >= .45 -> pts += 9
            m.awayAwayLossRate >= .30 -> pts += 4
        }

        val diff = m.homeFormPoints5!! - m.awayFormPoints5!!
        if (diff >= 4) { pts += 12; reasons += "forma recente casa superiore" }
        else if (diff in -5..-1) { pts += 7; reasons += "casa dietro ultime 5: pattern test" }
        else if (diff >= 0) pts += 5

        val adjustment = ((pts - 50) / 1000.0).coerceIn(-0.04, 0.07)
        val p = min(0.82, max(0.25, marketP + adjustment))
        val ev = (p * m.odd1) - 1.0
        val fair = 1.0 / p
        val verdict = when {
            pts >= 78 && ev > .05 -> Verdict.STRONG
            pts >= 68 && ev > .02 -> Verdict.PRUDENT
            else -> Verdict.PASS
        }

        val sourceStatus = if (m.notes.startsWith("Dati completi")) m.notes else "Dati completi"
        return m.copy(
            score = pts.coerceAtMost(100),
            estimatedP = p,
            fairOdd = fair,
            ev = ev,
            verdict = verdict,
            notes = reasons.joinToString(" · ") + " · $sourceStatus"
        )
    }
}
