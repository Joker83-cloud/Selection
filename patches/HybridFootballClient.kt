package com.onestake.selectionai

class HybridFootballClient(@Suppress("UNUSED_PARAMETER") private val apiKey: String) {
    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        return runCatching { DirettaMobileClient().enrich(candidate, date) }
            .getOrElse { candidate.copy(notes = "Diretta non disponibile: ${it.message}") }
    }
}
