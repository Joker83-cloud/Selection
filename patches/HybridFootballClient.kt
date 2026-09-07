package com.onestake.selectionai

class HybridFootballClient(private val apiKey: String) {
    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val apiResult = runCatching { ApiFootballClient(apiKey).enrich(candidate, date) }
            .getOrElse { candidate.copy(notes = "API-Football non disponibile: ${it.message}") }

        if (hasEnoughCoreData(apiResult)) {
            return apiResult.copy(notes = mergeNote(apiResult.notes, "Fonte: API-Football"))
        }

        val direttaResult = runCatching { DirettaMobileClient().enrich(candidate, date) }
            .getOrElse { candidate.copy(notes = mergeNote(apiResult.notes, "Diretta non disponibile: ${it.message}")) }

        val chosen = chooseMoreComplete(apiResult, direttaResult)
        val source = if (chosen === direttaResult || scoreCompleteness(direttaResult) >= scoreCompleteness(apiResult)) {
            "Fonte fallback: Diretta mobile"
        } else {
            "Fonte: API-Football parziale"
        }
        return chosen.copy(notes = mergeNote(chosen.notes, source))
    }

    private fun hasEnoughCoreData(item: MatchCandidate): Boolean =
        item.homeRank != null && item.awayRank != null &&
            item.homeHomeWinRate != null && item.awayAwayLossRate != null &&
            item.homeFormPoints5 != null && item.awayFormPoints5 != null

    private fun scoreCompleteness(item: MatchCandidate): Int {
        var s = 0
        if (item.homeRank != null) s += 2
        if (item.awayRank != null) s += 2
        if (item.homeHomeWinRate != null) s += 2
        if (item.awayAwayLossRate != null) s += 2
        if (item.homeFormPoints5 != null) s += 2
        if (item.awayFormPoints5 != null) s += 2
        if (!item.leagueName.orEmpty().isBlank()) s += 1
        if (item.fixtureId != null) s += 1
        return s
    }

    private fun chooseMoreComplete(a: MatchCandidate, b: MatchCandidate): MatchCandidate {
        val sa = scoreCompleteness(a)
        val sb = scoreCompleteness(b)
        if (sb > sa) return b
        if (sa > sb) return a

        return a.copy(
            fixtureId = a.fixtureId ?: b.fixtureId,
            leagueId = a.leagueId ?: b.leagueId,
            leagueName = a.leagueName.orEmpty().ifBlank { b.leagueName.orEmpty() },
            season = a.season ?: b.season,
            homeTeamId = a.homeTeamId ?: b.homeTeamId,
            awayTeamId = a.awayTeamId ?: b.awayTeamId,
            homeRank = a.homeRank ?: b.homeRank,
            awayRank = a.awayRank ?: b.awayRank,
            homeFormPoints5 = a.homeFormPoints5 ?: b.homeFormPoints5,
            awayFormPoints5 = a.awayFormPoints5 ?: b.awayFormPoints5,
            homeHomeWinRate = a.homeHomeWinRate ?: b.homeHomeWinRate,
            awayAwayLossRate = a.awayAwayLossRate ?: b.awayAwayLossRate,
            notes = mergeNote(a.notes, b.notes)
        )
    }

    private fun mergeNote(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }
}
