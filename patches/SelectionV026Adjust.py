from pathlib import Path
import sys

hybrid = Path(sys.argv[1])
scoring = Path(sys.argv[2])

s = hybrid.read_text()

old = '''    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val homeTeam = findTeam(candidate.home)
            ?: return candidate.copy(notes = merge(candidate.notes, "Fallback SofaScore: squadra casa non identificata"))

        val homeNext = loadEvents(homeTeam.id, "next")
        val homeLast = loadEvents(homeTeam.id, "last")

        var awayTeam = findTeam(candidate.away)
        var directEvent: Event? = null
'''
new = '''    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        // v0.26: prima prova ad agganciare la partita direttamente dal palinsesto SofaScore del giorno.
        // Questo evita di dipendere dalla ricerca per nome della singola squadra, che fallisce spesso con alias/OCR.
        val scheduled = loadScheduled(date)
        val scheduledEvent = scheduled
            .map { it to (similarity(candidate.home, it.home) + similarity(candidate.away, it.away)) }
            .filter { (e, score) ->
                categoryCompatible(candidate.home, e.home) && categoryCompatible(candidate.away, e.away) &&
                    similarity(candidate.home, e.home) >= 0.58 && similarity(candidate.away, e.away) >= 0.58 && score >= 1.35
            }
            .maxByOrNull { it.second }
            ?.first

        val homeTeam = scheduledEvent?.let { TeamHit(it.homeId, it.home) } ?: findTeam(candidate.home)
            ?: return candidate.copy(notes = merge(candidate.notes, "Fallback SofaScore: squadra casa non identificata"))

        val homeNext = loadEvents(homeTeam.id, "next")
        val homeLast = loadEvents(homeTeam.id, "last")

        var awayTeam = scheduledEvent?.let { TeamHit(it.awayId, it.away) } ?: findTeam(candidate.away)
        var directEvent: Event? = scheduledEvent
'''
if old not in s:
    raise SystemExit('Hybrid enrich anchor not found')
s = s.replace(old, new, 1)

marker = '''    private fun loadEvents(teamId: Int, mode: String): List<Event> {'''
insert = '''    private fun loadScheduled(date: String): List<Event> {
        val out = mutableListOf<Event>()
        val json = runCatching { JSONObject(fetch("$base/sport/football/scheduled-events/$date")) }.getOrNull()
            ?: return out
        val arr = json.optJSONArray("events") ?: return out
        parseEvents(arr, out)
        return out
    }

'''
if marker not in s:
    raise SystemExit('Hybrid loadEvents anchor not found')
s = s.replace(marker, insert + marker, 1)

# Make team-name matching slightly more tolerant only after category compatibility checks.
s = s.replace('categoryCompatible(expected, actual) && similarity(expected, actual) >= 0.68',
              'categoryCompatible(expected, actual) && similarity(expected, actual) >= 0.60', 1)

hybrid.write_text(s)

q = scoring.read_text()

# Missing standings alone must not kill the whole analysis: cups/small leagues often have no usable table.
q = q.replace('''        if (m.homeRank == null || m.awayRank == null) missing += "classifica"\n''', '', 1)
q = q.replace('''        val rankGap = m.awayRank!! - m.homeRank!!\n        val formDiff = m.homeFormPoints5!! - m.awayFormPoints5!!\n''', '''        val rankKnown = m.homeRank != null && m.awayRank != null
        val rankGap = if (rankKnown) m.awayRank!! - m.homeRank!! else 0
        val formDiff = m.homeFormPoints5!! - m.awayFormPoints5!!
''', 1)
q = q.replace('''        val closeness = (if (abs(rankGap) <= 2) .016 else if (abs(rankGap) <= 4) .008 else -.006) +
            (if (abs(formDiff) <= 2) .014 else if (abs(formDiff) <= 4) .006 else -.005)
''', '''        val rankCloseness = if (!rankKnown) 0.0 else if (abs(rankGap) <= 2) .016 else if (abs(rankGap) <= 4) .008 else -.006
        val closeness = rankCloseness +
            (if (abs(formDiff) <= 2) .014 else if (abs(formDiff) <= 4) .006 else -.005)
''', 1)
q = q.replace('''            val score = (58.0 + ev * 220.0 + edge * 260.0 + context).toInt().coerceIn(0, 100)\n''', '''            val dataPenalty = if (rankKnown) 0 else 6
            val score = (58.0 + ev * 220.0 + edge * 260.0 + context - dataPenalty).toInt().coerceIn(0, 100)
''', 1)
q = q.replace('''        reasons += "forma pesata ${m.homeFormPoints5}/${m.awayFormPoints5}"\n''', '''        reasons += "forma pesata ${m.homeFormPoints5}/${m.awayFormPoints5}"
        if (!rankKnown) reasons += "classifica non disponibile: peso ridotto"
''', 1)

scoring.write_text(q)
