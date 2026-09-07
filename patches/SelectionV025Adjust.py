from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

# One-time reset of the pre-v0.25 learning history. New history is preserved on later launches.
old = '''        val store = HistoryStore(this)
        setContent { MaterialTheme { OneStakeApp(store) } }'''
new = '''        val store = HistoryStore(this)
        val migration = getSharedPreferences("onestake_selection_migrations", Context.MODE_PRIVATE)
        if (!migration.getBoolean("history_reset_v025", false)) {
            store.save(emptyList())
            migration.edit().putBoolean("history_reset_v025", true).apply()
        }
        setContent { MaterialTheme { OneStakeApp(store) } }'''
s = s.replace(old, new, 1)

# The configured range applies to the selected 1/X/2 market, not only to the home win.
s = s.replace(
    'private fun isOneStakeEligible(item: MatchCandidate, minOdd: Double, maxOdd: Double): Boolean = item.odd1 in minOdd..maxOdd',
    'private fun isOneStakeEligible(item: MatchCandidate, minOdd: Double, maxOdd: Double): Boolean = listOf(item.odd1, item.oddX, item.odd2).any { it in minOdd..maxOdd }',
    1
)

# Use calibration that is aware of the actually selected market.
s = s.replace(
    'return AdaptiveCalibrator.calibrate(ScoringEngine.score(enriched, minOdd, maxOdd), history)',
    'return calibrateSelectedMarket(ScoringEngine.score(enriched, minOdd, maxOdd), history)',
    1
)

# UI wording and selected-market display.
s = s.replace('Filtro quota aggiornato:', 'Filtro quota 1/X/2 aggiornato:')
s = s.replace('candidate · ${merged.size - eligible} fuori filtro nascoste.', 'candidate 1/X/2 · ${merged.size - eligible} fuori filtro nascoste.')
s = s.replace(
    'Text("${item.verdict} · Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · P ${(item.estimatedP * 100).fmt1()}% · Fair ${item.fairOdd.fmt2()} · Cl. ${item.homeRank ?: "?"}/${item.awayRank ?: "?"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)',
    'Text("🎯 ${item.selectedMarket()} @${item.selectedOdd().fmt2()} · ${item.verdict} · Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · P ${(item.estimatedP * 100).fmt1()}% · Fair ${item.fairOdd.fmt2()} · Cl. ${item.homeRank ?: "?"}/${item.awayRank ?: "?"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)'
)

# History ROI and every correction/list row must use the actual selected odd.
s = s.replace('it.odd1 - 1.0', 'it.selectedOdd() - 1.0')
s = s.replace('· 1 @${item.odd1.fmt2()}', '· ${item.selectedMarket()} @${item.selectedOdd().fmt2()}')
s = s.replace('| 1 @${x.odd1.fmt2()} |', '| ${x.selectedMarket()} @${x.selectedOdd().fmt2()} |')
s = s.replace('Mercato: 1 fisso', 'Mercati valutati: 1 / X / 2 · salvato il miglior valore')
s = s.replace('Range quota 1:', 'Range quota selezione 1/X/2:')
s = s.replace('Filtro corrente quota 1:', 'Filtro corrente quota 1/X/2:')
s = s.replace('Solo mercato 1 fisso nel range scelto. Il motore deve imparare quali profili di partita funzionano meglio e correggere gradualmente la probabilità stimata.',
              'Mercati 1, X e 2 nel range scelto. Il motore confronta i tre esiti, salva quello con il miglior valore e calibra gradualmente ogni mercato sugli esiti registrati.')

# Add adaptive calibration by selected market. It starts slowly and is capped to avoid overfitting.
anchor = 'private fun buildSelectionReport('
calibrator = r'''private fun calibrateSelectedMarket(scored: MatchCandidate, history: List<MatchCandidate>): MatchCandidate {
    if (scored.verdict == Verdict.NEEDS_DATA) return scored
    val market = scored.selectedMarket()
    val settled = history.filter { it.result != BetResult.PENDING && it.selectedMarket() == market }
    val n = settled.size
    if (n < 10) return scored.copy(notes = scored.notes + " · Calibrazione $market: raccolta $n/10")

    val won = settled.count { it.result == BetResult.WON }
    val empirical = won.toDouble() / n.toDouble()
    val weight = (((n - 10).coerceAtMost(90)) / 90.0 * 0.15).coerceIn(0.0, 0.15)
    val p = (scored.estimatedP * (1.0 - weight) + empirical * weight).coerceIn(0.08, 0.82)
    val odd = scored.selectedOdd()
    val ev = p * odd - 1.0
    val fair = 1.0 / p
    val edge = p - 1.0 / odd
    val verdict = when {
        scored.score >= 80 && ev >= .06 && edge >= .025 -> Verdict.STRONG
        scored.score >= 70 && ev >= .03 && edge >= .015 -> Verdict.PRUDENT
        else -> Verdict.PASS
    }
    return scored.copy(
        estimatedP = p,
        fairOdd = fair,
        ev = ev,
        verdict = verdict,
        notes = scored.notes + " · Calibrazione $market: n=$n peso ${(weight * 100).fmt1()}% WR ${(empirical * 100).fmt1()}%"
    )
}

'''
if 'private fun calibrateSelectedMarket(' not in s:
    s = s.replace(anchor, calibrator + anchor, 1)

p.write_text(s)
