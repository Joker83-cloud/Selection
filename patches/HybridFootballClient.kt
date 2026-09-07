package com.onestake.selectionai

class HybridFootballClient(@Suppress("UNUSED_PARAMETER") private val apiKey: String) {
    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        val diretta = runCatching { DirettaMobileClient().enrich(candidate, date) }
            .getOrElse { candidate.copy(notes = "Diretta non disponibile: ${it.message}") }

        val needsFallback = diretta.leagueName != null && (
            diretta.homeFormPoints5 == null ||
                diretta.awayFormPoints5 == null ||
                diretta.homeHomeWinRate == null ||
                diretta.awayAwayLossRate == null
            )

        if (!needsFallback) return diretta

        return runCatching { SofascoreFallbackClient().enrich(diretta) }
            .getOrElse { diretta.copy(notes = merge(diretta.notes, "Fallback SofaScore non disponibile: ${it.message}")) }
    }

    private fun merge(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a.contains(b) -> a
        else -> "$a · $b"
    }
}
