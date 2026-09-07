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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

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

@Composable
fun AnalyzeScreen(apiKey: String, history: List<MatchCandidate>, onSaved: (MatchCandidate) -> Unit) {
    var candidates by remember { mutableStateOf(emptyList<MatchCandidate>()) }
    var status by remember { mutableStateOf("Allega uno o più screenshot con squadre e quote 1-X-2.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun analyzeCandidate(item: MatchCandidate): MatchCandidate {
        val enriched = withContext(Dispatchers.IO) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                ApiFootballClient(apiKey).enrich(item, date)
            } catch (e: Exception) {
                item.copy(notes = "API: ${e.message}")
            }
        }
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
