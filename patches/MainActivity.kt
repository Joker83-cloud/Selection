package com.onestake.selectionai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = HistoryStore(this)
        setContent { MaterialTheme { OneStakeApp(store) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneStakeApp(store: HistoryStore) {
    var tab by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf(store.apiKey()) }
    var history by remember { mutableStateOf(store.load()) }
    val tabs = listOf("ANALIZZA", "STORICO", "DATI")

    Scaffold(topBar = { TopAppBar(title = { Text("OneStake · Selection AI") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelLarge) })
                }
            }
            when (tab) {
                0 -> AnalyzeScreen(apiKey = apiKey, history = history, onSaved = { item ->
                    history = (history + item).takeLast(500)
                    store.save(history)
                })
                1 -> HistoryScreen(history = history, onChange = { updated -> history = updated; store.save(updated) })
                2 -> SettingsScreen(apiKey = apiKey, onApiKey = { apiKey = it; store.setApiKey(it) })
            }
        }
    }
}

private fun isOneStakeEligible(item: MatchCandidate): Boolean = item.odd1 in 1.50..2.50

private fun hasCoreData(item: MatchCandidate): Boolean =
    item.fixtureId != null && item.leagueId != null && item.homeTeamId != null && item.awayTeamId != null &&
        item.homeRank != null && item.awayRank != null &&
        item.homeHomeWinRate != null && item.awayAwayLossRate != null &&
        item.homeFormPoints5 != null && item.awayFormPoints5 != null

@Composable
fun AnalyzeScreen(apiKey: String, history: List<MatchCandidate>, onSaved: (MatchCandidate) -> Unit) {
    var candidates by remember { mutableStateOf(emptyList<MatchCandidate>()) }
    var status by remember { mutableStateOf("Allega uno o più screenshot con squadre e quote 1-X-2.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun analyzeCandidate(item: MatchCandidate): MatchCandidate {
        val enrichedRaw = withContext(Dispatchers.IO) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                RobustApiFootballClient(apiKey).enrich(item, date)
            } catch (e: Exception) {
                item.copy(notes = "API: ${e.message}")
            }
        }
        val enriched = if (hasCoreData(enrichedRaw)) enrichedRaw else enrichedRaw.copy(homeRank = null, awayRank = null)
        return AdaptiveCalibrator.calibrate(ScoringEngine.score(enriched), history)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        status = "Lettura di ${uris.size} screenshot…"
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val collected = Collections.synchronizedList(mutableListOf<MatchCandidate>())
        val remaining = AtomicInteger(uris.size)

        fun finishOne() {
            if (remaining.decrementAndGet() == 0) {
                val merged = collected.distinctBy {
                    "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}"
                }
                candidates = merged
                val eligible = merged.count(::isOneStakeEligible)
                status = if (merged.isEmpty()) {
                    "Nessun match riconosciuto."
                } else {
                    "${merged.size} match riconosciuti · $eligible nella fascia 1,50–2,50."
                }
                busy = false
            }
        }

        uris.forEach { uri ->
            runCatching { InputImage.fromFilePath(context, uri) }
                .onFailure { finishOne() }
                .onSuccess { image ->
                    recognizer.process(image)
                        .addOnSuccessListener { text ->
                            collected += OcrParser.parse(text)
                            finishOne()
                        }
                        .addOnFailureListener { finishOne() }
                }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Button(onClick = { launcher.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "ELABORAZIONE…" else "📷 ALLEGA SCREENSHOT")
        }

        if (candidates.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            val eligibleCount = candidates.count(::isOneStakeEligible)
            Button(
                onClick = {
                    scope.launch {
                        if (apiKey.isBlank()) {
                            status = "Inserisci prima la API key nella scheda DATI."
                            return@launch
                        }
                        val validIndices = candidates.indices.filter { isOneStakeEligible(candidates[it]) }
                        if (validIndices.isEmpty()) {
                            status = "Nessuna partita nella fascia quota 1,50–2,50."
                            return@launch
                        }
                        busy = true
                        var working = candidates
                        validIndices.forEachIndexed { pos, index ->
                            val current = working[index]
                            status = "Analisi ${pos + 1}/${validIndices.size}: ${current.home} – ${current.away}"
                            val scored = analyzeCandidate(current)
                            working = working.toMutableList().also { it[index] = scored }
                            candidates = working
                        }
                        val playable = working.filter { isOneStakeEligible(it) && it.verdict != Verdict.NEEDS_DATA }
                        val positive = playable.count { it.verdict != Verdict.PASS }
                        status = "Analisi completata: $positive selezioni utili su ${validIndices.size} candidate."
                        busy = false
                    }
                },
                enabled = !busy && eligibleCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (apiKey.isBlank()) "🔑 INSERISCI API KEY" else "⚡ ANALIZZA TUTTE ($eligibleCount)")
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            itemsIndexed(candidates, key = { _, it -> it.id }) { index, item ->
                CandidateCard(
                    item = item,
                    apiEnabled = apiKey.isNotBlank(),
                    eligible = isOneStakeEligible(item),
                    onEdit = { changed -> candidates = candidates.toMutableList().also { it[index] = changed } },
                    onAnalyze = {
                        scope.launch {
                            if (!isOneStakeEligible(item)) {
                                status = "${item.home}: quota 1 fuori dalla fascia 1,50–2,50."
                                return@launch
                            }
                            busy = true
                            status = "Recupero dati per ${item.home}…"
                            val scored = analyzeCandidate(item)
                            candidates = candidates.toMutableList().also { it[index] = scored }
                            status = "Analisi completata."
                            busy = false
                        }
                    },
                    onSave = { onSaved(item) }
                )
            }
        }
    }
}

@Composable
fun CandidateCard(
    item: MatchCandidate,
    apiEnabled: Boolean,
    eligible: Boolean,
    onEdit: (MatchCandidate) -> Unit,
    onAnalyze: () -> Unit,
    onSave: () -> Unit
) {
    var home by remember(item.id) { mutableStateOf(item.home) }
    var away by remember(item.id) { mutableStateOf(item.away) }
    var o1 by remember(item.id) { mutableStateOf(item.odd1.toString()) }
    var ox by remember(item.id) { mutableStateOf(item.oddX.toString()) }
    var o2 by remember(item.id) { mutableStateOf(item.odd2.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(7.dp)) {
            Text(
                if (eligible) "✅ CANDIDATA · quota 1 nella fascia" else "⛔ FUORI FILTRO · quota 1 richiesta 1,50–2,50",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = home,
                    onValueChange = { home = it; onEdit(item.copy(home = it)) },
                    label = { Text("Casa", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp)
                )
                OutlinedTextField(
                    value = away,
                    onValueChange = { away = it; onEdit(item.copy(away = it)) },
                    label = { Text("Ospite", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OddsField("1", o1, Modifier.weight(1f)) { o1 = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(odd1 = v)) } }
                OddsField("X", ox, Modifier.weight(1f)) { ox = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(oddX = v)) } }
                OddsField("2", o2, Modifier.weight(1f)) { o2 = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(odd2 = v)) } }
            }
            if (item.verdict != Verdict.NEEDS_DATA) {
                Spacer(Modifier.height(3.dp))
                Text("Score ${item.score}/100 · ${item.verdict} · EV ${(item.ev * 100).fmt1()}%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text("P ${(item.estimatedP * 100).fmt1()}% · Fair ${item.fairOdd.fmt2()} · Classifica ${item.homeRank ?: "?"}ª/${item.awayRank ?: "?"}ª", style = MaterialTheme.typography.labelSmall)
            }
            if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(
                    onClick = onAnalyze,
                    enabled = apiEnabled && eligible,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text(
                        when {
                            !eligible -> "FUORI FILTRO"
                            apiEnabled -> "ANALIZZA"
                            else -> "API KEY"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    Text("SALVA", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun OddsField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.heightIn(min = 48.dp)
    )
}

@Composable
fun HistoryScreen(history: List<MatchCandidate>, onChange: (List<MatchCandidate>) -> Unit) {
    val settled = history.filter { it.result != BetResult.PENDING }
    val won = settled.count { it.result == BetResult.WON }
    val staked = settled.size.toDouble()
    val profit = settled.sumOf { if (it.result == BetResult.WON) it.odd1 - 1.0 else -1.0 }
    val roi = if (staked == 0.0) 0.0 else profit / staked

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Casi: ${settled.size} · Vinte: $won · Win ${(if (settled.isEmpty()) 0.0 else won * 100.0 / settled.size).fmt1()}% · ROI ${(roi * 100).fmt1()}%", fontWeight = FontWeight.Bold)
        Text("Statistiche calcolate con stake virtuale 1 unità, separate dal motore stake OneStake.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(history.reversed(), key = { _, it -> it.id }) { reverseIndex, item ->
                val realIndex = history.lastIndex - reverseIndex
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${item.home} – ${item.away} · 1 @${item.odd1.fmt2()}", fontWeight = FontWeight.Bold)
                        Text("Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · ${item.verdict}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.WON) }) }) { Text("VINTA") }
                            Button(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.LOST) }) }) { Text("PERSA") }
                            OutlinedButton(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.PENDING) }) }) { Text("PEND.") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(apiKey: String, onApiKey: (String) -> Unit) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Dati calcistici", fontWeight = FontWeight.Bold)
        Text("Inserisci la tua API key API-Football. Viene salvata solo sul dispositivo.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API-Football key") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onApiKey(key) }, modifier = Modifier.fillMaxWidth()) { Text("SALVA API KEY") }
        Spacer(Modifier.height(18.dp))
        Text("Filtro OneStake", fontWeight = FontWeight.Bold)
        Text("• quota 1 ammessa: 1,50–2,50\n• le quote fuori fascia vengono riconosciute ma non analizzate\n• ANALIZZA TUTTE processa solo le candidate valide\n• identificazione robusta fixture/campionato\n• classifica generale (tutti i gruppi)\n• rendimento casa/trasferta con fallback statistiche squadra\n• punti ultime 5\n• nessun verdetto se mancano dati basilari\n• probabilità 1X2 normalizzata per overround\n• EV >2% per PRUDENTE, >5% per FORTE\n• apprendimento automatico dei pesi NON ancora attivo: prima raccogliamo un campione pulito.")
    }
}

private fun Double.fmt1() = String.format(Locale.US, "%.1f", this)
private fun Double.fmt2() = String.format(Locale.US, "%.2f", this)

private class RobustApiFootballClient(private val apiKey: String) {
    private val base = "https://v3.football.api-sports.io"

    fun enrich(candidate: MatchCandidate, date: String): MatchCandidate {
        if (apiKey.isBlank()) return candidate.copy(notes = "API key mancante")
        val fixture = findFixtureRobust(candidate, date)
            ?: return candidate.copy(notes = "Partita non identificata con certezza via API-Football")

        val league = fixture.getJSONObject("league")
        val teams = fixture.getJSONObject("teams")
        val homeObj = teams.getJSONObject("home")
        val awayObj = teams.getJSONObject("away")
        val leagueId = league.getInt("id")
        val season = league.getInt("season")
        val homeId = homeObj.getInt("id")
        val awayId = awayObj.getInt("id")

        val rows = flattenStandings(get("/standings?league=$leagueId&season=$season"))
        var homeRank: Int? = null
        var awayRank: Int? = null
        var homeHomeWinRate: Double? = null
        var awayAwayLossRate: Double? = null

        for (row in rows) {
            val teamId = row.optJSONObject("team")?.optInt("id") ?: continue
            if (teamId == homeId) {
                homeRank = row.optInt("rank").takeIf { it > 0 }
                homeHomeWinRate = ratio(row.optJSONObject("home"), "win")
            }
            if (teamId == awayId) {
                awayRank = row.optInt("rank").takeIf { it > 0 }
                awayAwayLossRate = ratio(row.optJSONObject("away"), "lose")
            }
        }

        if (homeRank == null) {
            findStandingRowForTeam(leagueId, season, homeId)?.let { row ->
                homeRank = row.optInt("rank").takeIf { it > 0 }
                if (homeHomeWinRate == null) homeHomeWinRate = ratio(row.optJSONObject("home"), "win")
            }
        }
        if (awayRank == null) {
            findStandingRowForTeam(leagueId, season, awayId)?.let { row ->
                awayRank = row.optInt("rank").takeIf { it > 0 }
                if (awayAwayLossRate == null) awayAwayLossRate = ratio(row.optJSONObject("away"), "lose")
            }
        }

        if (homeHomeWinRate == null) homeHomeWinRate = teamHomeWinRate(leagueId, season, homeId)
        if (awayAwayLossRate == null) awayAwayLossRate = teamAwayLossRate(leagueId, season, awayId)

        val homePts5 = recentPoints(homeId)
        val awayPts5 = recentPoints(awayId)

        val missing = mutableListOf<String>()
        if (homeRank == null || awayRank == null) missing += "classifica"
        if (homeHomeWinRate == null) missing += "rendimento casa"
        if (awayAwayLossRate == null) missing += "rendimento trasferta"
        if (homePts5 == null || awayPts5 == null) missing += "ultime 5"

        val note = if (missing.isEmpty()) {
            "Dati completi · ${league.optString("name")} · classifica/forma/casa-trasferta OK"
        } else {
            "Mancanti: ${missing.distinct().joinToString(", ")} · ${league.optString("name")}" 
        }

        return candidate.copy(
            fixtureId = fixture.getJSONObject("fixture").getInt("id"),
            leagueId = leagueId,
            leagueName = league.optString("name"),
            season = season,
            homeTeamId = homeId,
            awayTeamId = awayId,
            homeRank = homeRank,
            awayRank = awayRank,
            homeFormPoints5 = homePts5,
            awayFormPoints5 = awayPts5,
            homeHomeWinRate = homeHomeWinRate,
            awayAwayLossRate = awayAwayLossRate,
            notes = note
        )
    }

    private fun ratio(split: JSONObject?, resultKey: String): Double? {
        if (split == null) return null
        val played = split.optInt("played")
        val value = split.optInt(resultKey)
        return if (played > 0) value.toDouble() / played else null
    }

    private fun flattenStandings(data: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val response = data.optJSONArray("response") ?: return out
        for (i in 0 until response.length()) {
            val league = response.optJSONObject(i)?.optJSONObject("league") ?: continue
            val groups = league.optJSONArray("standings") ?: continue
            for (g in 0 until groups.length()) {
                val table = groups.optJSONArray(g) ?: continue
                for (r in 0 until table.length()) table.optJSONObject(r)?.let(out::add)
            }
        }
        return out
    }

    private fun findStandingRowForTeam(leagueId: Int, season: Int, teamId: Int): JSONObject? = try {
        flattenStandings(get("/standings?league=$leagueId&season=$season&team=$teamId"))
            .firstOrNull { it.optJSONObject("team")?.optInt("id") == teamId }
    } catch (_: Exception) { null }

    private fun teamHomeWinRate(leagueId: Int, season: Int, teamId: Int): Double? = try {
        val stats = get("/teams/statistics?league=$leagueId&season=$season&team=$teamId").optJSONObject("response") ?: return null
        val fixtures = stats.optJSONObject("fixtures") ?: return null
        val played = fixtures.optJSONObject("played")?.optInt("home") ?: 0
        val wins = fixtures.optJSONObject("wins")?.optInt("home") ?: 0
        if (played > 0) wins.toDouble() / played else null
    } catch (_: Exception) { null }

    private fun teamAwayLossRate(leagueId: Int, season: Int, teamId: Int): Double? = try {
        val stats = get("/teams/statistics?league=$leagueId&season=$season&team=$teamId").optJSONObject("response") ?: return null
        val fixtures = stats.optJSONObject("fixtures") ?: return null
        val played = fixtures.optJSONObject("played")?.optInt("away") ?: 0
        val losses = fixtures.optJSONObject("loses")?.optInt("away") ?: 0
        if (played > 0) losses.toDouble() / played else null
    } catch (_: Exception) { null }

    private fun recentPoints(teamId: Int): Int? = try {
        val arr = get("/fixtures?team=$teamId&last=5&status=FT").optJSONArray("response") ?: return null
        if (arr.length() == 0) return null
        var pts = 0
        var counted = 0
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val teams = f.optJSONObject("teams") ?: continue
            val home = teams.optJSONObject("home") ?: continue
            val away = teams.optJSONObject("away") ?: continue
            val isHome = home.optInt("id") == teamId
            val meWinner = if (isHome) home.opt("winner") else away.opt("winner")
            val otherWinner = if (isHome) away.opt("winner") else home.opt("winner")
            pts += when {
                meWinner == true -> 3
                otherWinner == true -> 0
                else -> 1
            }
            counted++
        }
        if (counted > 0) pts else null
    } catch (_: Exception) { null }

    private fun findFixtureRobust(c: MatchCandidate, date: String): JSONObject? {
        findFixtureOnDate(c, date, 0.62)?.let { return it }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val parsed = runCatching { sdf.parse(date) }.getOrNull() ?: return null
        for (delta in listOf(-1, 1)) {
            val cal = Calendar.getInstance().apply { time = parsed; add(Calendar.DAY_OF_MONTH, delta) }
            findFixtureOnDate(c, sdf.format(cal.time), 0.68)?.let { return it }
        }
        return null
    }

    private fun findFixtureOnDate(c: MatchCandidate, date: String, threshold: Double): JSONObject? {
        val arr = get("/fixtures?date=${URLEncoder.encode(date, "UTF-8")}").optJSONArray("response") ?: return null
        var best: JSONObject? = null
        var bestScore = 0.0
        var secondScore = 0.0
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val teams = f.optJSONObject("teams") ?: continue
            val h = teams.optJSONObject("home")?.optString("name").orEmpty()
            val a = teams.optJSONObject("away")?.optString("name").orEmpty()
            val score = similarity(c.home, h) * 0.5 + similarity(c.away, a) * 0.5
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = f
            } else if (score > secondScore) secondScore = score
        }
        return if (bestScore >= threshold && (bestScore - secondScore >= 0.08 || bestScore >= 0.86)) best else null
    }

    private fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isBlank() || y.isBlank()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.93
        val xa = x.split(' ').filter { it.length > 1 }.toSet()
        val ya = y.split(' ').filter { it.length > 1 }.toSet()
        val token = if (xa.isEmpty() || ya.isEmpty()) 0.0 else xa.intersect(ya).size.toDouble() / max(xa.size, ya.size)
        val edit = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length).coerceAtLeast(1)
        return (token * 0.62 + edit * 0.38).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\b(fc|cf|sc|ac|afc|club|de|the)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun get(path: String): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("x-apisports-key", apiKey)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("API HTTP $code: ${body.take(180)}")
            val json = JSONObject(body)
            val errors = json.opt("errors")
            if (errors is JSONObject && errors.length() > 0) throw IllegalStateException("API: ${errors.toString().take(180)}")
            json
        } finally {
            conn.disconnect()
        }
    }
}
