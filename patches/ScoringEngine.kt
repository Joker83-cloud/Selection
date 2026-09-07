package com.onestake.selectionai

import kotlin.math.max
import kotlin.math.min

object ScoringEngine {
    fun score(m: MatchCandidate): MatchCandidate {
        val impliedRaw = 1.0 / m.odd1
        val overround = (1.0 / m.odd1) + (1.0 / m.oddX) + (1.0 / m.odd2)
        val marketP = impliedRaw / overround
        val breakEvenP = 1.0 / m.odd1

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

        if (m.odd1 !in 1.50..2.50) {
            return m.copy(
                score = 0,
                estimatedP = marketP,
                fairOdd = 1.0 / marketP,
                ev = 0.0,
                verdict = Verdict.PASS,
                notes = "Quota 1 fuori range OneStake"
            )
        }

        var pts = 0
        val reasons = mutableListOf<String>()

        // 1) Quota: premiamo la fascia preferita, ma non scegliamo automaticamente la quota più bassa.
        when (m.odd1) {
            in 1.50..1.80 -> { pts += 16; reasons += "quota target" }
            in 1.81..2.10 -> { pts += 14; reasons += "quota buona" }
            in 2.11..2.30 -> pts += 9
            else -> pts += 5
        }

        // 2) Classifica generale: vantaggio casa è il pattern più solido; il rimbalzo resta sperimentale.
        val rankGap = m.awayRank!! - m.homeRank!!
        when {
            rankGap >= 8 -> { pts += 20; reasons += "forte vantaggio classifica" }
            rankGap >= 4 -> { pts += 16; reasons += "casa nettamente avanti" }
            rankGap >= 1 -> { pts += 12; reasons += "casa avanti in classifica" }
            rankGap == 0 -> pts += 6
            else -> { pts += 2; reasons += "casa dietro in classifica" }
        }

        // 3) Rendimento specifico casa/trasferta: è il nucleo del modello.
        when {
            m.homeHomeWinRate!! >= .70 -> { pts += 22; reasons += "rendimento casa molto forte" }
            m.homeHomeWinRate >= .60 -> { pts += 18; reasons += "forte rendimento casa" }
            m.homeHomeWinRate >= .50 -> pts += 13
            m.homeHomeWinRate >= .40 -> pts += 7
            else -> { pts -= 6; reasons += "rendimento casa debole" }
        }

        when {
            m.awayAwayLossRate!! >= .65 -> { pts += 18; reasons += "ospite molto fragile fuori" }
            m.awayAwayLossRate >= .55 -> { pts += 15; reasons += "ospite fragile fuori" }
            m.awayAwayLossRate >= .45 -> pts += 10
            m.awayAwayLossRate >= .35 -> pts += 5
            else -> { pts -= 4; reasons += "ospite solida fuori" }
        }

        // 4) Ultime 5: il vantaggio recente è positivo; il pattern "casa dietro" resta solo un piccolo bonus test.
        val formDiff = m.homeFormPoints5!! - m.awayFormPoints5!!
        when {
            formDiff >= 6 -> { pts += 14; reasons += "forma recente casa molto superiore" }
            formDiff >= 3 -> { pts += 10; reasons += "forma recente casa superiore" }
            formDiff >= 0 -> pts += 6
            formDiff >= -3 -> { pts += 4; reasons += "casa leggermente dietro ultime 5: pattern test" }
            else -> { pts += 1; reasons += "casa nettamente dietro ultime 5" }
        }

        // Bonus sperimentale rimbalzo: solo se sostenuto da forte rendimento casalingo.
        if (rankGap < 0 && formDiff < 0 && m.homeHomeWinRate >= .60 && m.awayAwayLossRate >= .45) {
            pts += 6
            reasons += "rimbalzo supportato da casa/trasferta"
        }

        // Hard penalties: impediscono di promuovere favorite che non mostrano vera forza specifica.
        if (m.homeHomeWinRate < .40) pts -= 8
        if (m.awayAwayLossRate < .30) pts -= 6
        if (rankGap <= -5 && formDiff <= -4) pts -= 8

        val score = pts.coerceIn(0, 100)

        // Probabilità: mercato come ancora principale + dati reali casa/trasferta + piccoli aggiustamenti forma/classifica.
        val empirical = ((m.homeHomeWinRate + m.awayAwayLossRate) / 2.0).coerceIn(.20, .85)
        val rankAdj = when {
            rankGap >= 8 -> .035
            rankGap >= 4 -> .025
            rankGap >= 1 -> .012
            rankGap <= -8 -> -.035
            rankGap <= -4 -> -.025
            rankGap < 0 -> -.012
            else -> 0.0
        }
        val formAdj = (formDiff.coerceIn(-10, 10) * 0.0035)
        val modelRaw = (marketP * .58) + (empirical * .42) + rankAdj + formAdj
        val p = min(.80, max(.22, modelRaw))
        val ev = (p * m.odd1) - 1.0
        val fair = 1.0 / p
        val edge = p - breakEvenP

        // Selezione severa: niente "DA GIOCARE" senza edge reale sulla quota.
        val verdict = when {
            score >= 80 && ev >= .06 && edge >= .025 && m.homeHomeWinRate >= .50 -> Verdict.STRONG
            score >= 70 && ev >= .03 && edge >= .015 && m.homeHomeWinRate >= .45 -> Verdict.PRUDENT
            else -> Verdict.PASS
        }

        if (verdict == Verdict.PASS && ev < 0.0) reasons += "nessun valore sulla quota"
        if (verdict == Verdict.PASS && score >= 70 && ev < .03) reasons += "profilo buono ma edge insufficiente"

        return m.copy(
            score = score,
            estimatedP = p,
            fairOdd = fair,
            ev = ev,
            verdict = verdict,
            notes = reasons.distinct().joinToString(" · ") + " · Dati completi"
        )
    }
}
