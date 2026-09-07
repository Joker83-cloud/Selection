package com.onestake.selectionai

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    var history by remember { mutableStateOf(store.load()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("onestake_selection_ui", Context.MODE_PRIVATE) }
    var minOdd by remember { mutableDoubleStateOf(prefs.getFloat("min_odd", 1.50f).toDouble()) }
    var maxOdd by remember { mutableDoubleStateOf(prefs.getFloat("max_odd", 2.50f).toDouble()) }
    val tabs = listOf("ANALIZZA", "STORICO", "DATI")

    fun saveRange(min: Double, max: Double) {
        minOdd = min
        maxOdd = max
        prefs.edit().putFloat("min_odd", min.toFloat()).putFloat("max_odd", max.toFloat()).apply()
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Text("OneStake · Selection AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelMedium) })
                }
            }
            when (tab) {
                0 -> AnalyzeScreen(history, minOdd, maxOdd, ::saveRange) { item ->
                    history = (history + item).takeLast(500)
                    store.save(history)
                }
                1 -> HistoryScreen(history) { updated -> history = updated; store.save(updated) }
                2 -> DataScreen()
            }
        }
    }
}

private fun isOneStakeEligible(item: MatchCandidate, minOdd: Double, maxOdd: Double): Boolean = item.odd1 in minOdd..maxOdd

@Composable
fun AnalyzeScreen(
    history: List<MatchCandidate>,
    minOdd: Double,
    maxOdd: Double,
    onRangeChange: (Double, Double) -> Unit,
    onSaved: (MatchCandidate) -> Unit
) {
    var candidates by remember { mutableStateOf(emptyList<MatchCandidate>()) }
    var status by remember { mutableStateOf("Allega uno o più screenshot con squadre e quote 1-X-2.") }
    var busy by remember { mutableStateOf(false) }
    var minText by remember(minOdd) { mutableStateOf(minOdd.fmt2()) }
    var maxText by remember(maxOdd) { mutableStateOf(maxOdd.fmt2()) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun updateRange() {
        val lo = minText.replace(',', '.').toDoubleOrNull()
        val hi = maxText.replace(',', '.').toDoubleOrNull()
        if (lo != null && hi != null && lo > 1.0 && hi > lo && hi <= 10.0) {
            onRangeChange(lo, hi)
            status = "Filtro quota aggiornato: ${lo.fmt2()}–${hi.fmt2()}"
        } else status = "Filtro quota non valido: minimo < massimo."
    }

    suspend fun analyzeCandidate(item: MatchCandidate): MatchCandidate {
        val enriched = withContext(Dispatchers.IO) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                HybridFootballClient("").enrich(item, date)
            } catch (e: Exception) { item.copy(notes = "Dati: ${e.message}") }
        }
        return AdaptiveCalibrator.calibrate(ScoringEngine.score(enriched, minOdd, maxOdd), history)
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
                val merged = collected.distinctBy { "${it.home.lowercase()}|${it.away.lowercase()}|${it.odd1}|${it.oddX}|${it.odd2}" }
                candidates = merged
                val eligible = merged.count { isOneStakeEligible(it, minOdd, maxOdd) }
                status = when {
                    merged.isEmpty() -> "Nessun match riconosciuto."
                    eligible == 0 -> "${merged.size} match riconosciuti · nessuna candidata ${minOdd.fmt2()}–${maxOdd.fmt2()}."
                    else -> "$eligible candidate · ${merged.size - eligible} fuori filtro nascoste."
                }
                busy = false
            }
        }
        uris.forEach { uri ->
            runCatching { InputImage.fromFilePath(context, uri) }
                .onFailure { finishOne() }
                .onSuccess { image ->
                    recognizer.process(image).addOnSuccessListener { text -> collected += OcrParser.parse(text); finishOne() }
                        .addOnFailureListener { finishOne() }
                }
        }
    }

    val visibleCandidates = candidates.filter { isOneStakeEligible(it, minOdd, maxOdd) }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            CompactFilterField("Min", minText, Modifier.weight(1f)) { minText = it }
            CompactFilterField("Max", maxText, Modifier.weight(1f)) { maxText = it }
            Button(onClick = ::updateRange, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text("APPLICA", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(3.dp))
        Button(onClick = { launcher.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(42.dp), contentPadding = PaddingValues(vertical = 0.dp)) {
            Text(if (busy) "ELABORAZIONE…" else "📷 ALLEGA SCREENSHOT", style = MaterialTheme.typography.labelMedium)
        }
        if (visibleCandidates.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Button(onClick = {
                scope.launch {
                    val ids = visibleCandidates.map { it.id }.toSet()
                    val validIndices = candidates.indices.filter { candidates[it].id in ids }
                    busy = true
                    var working = candidates
                    validIndices.forEachIndexed { pos, index ->
                        val current = working[index]
                        status = "Analisi ${pos + 1}/${validIndices.size}: ${current.home} – ${current.away}"
                        val scored = analyzeCandidate(current)
                        working = working.toMutableList().also { it[index] = scored }
                        candidates = working
                    }
                    val playable = working.filter { isOneStakeEligible(it, minOdd, maxOdd) && it.verdict != Verdict.NEEDS_DATA }
                    val positive = playable.count { it.verdict != Verdict.PASS }
                    status = "Analisi completata: $positive utili su ${validIndices.size} candidate."
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(42.dp), contentPadding = PaddingValues(vertical = 0.dp)) {
                Text("⚡ ANALIZZA TUTTE (${visibleCandidates.size})", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(status, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visibleCandidates, key = { it.id }) { item ->
                CandidateCard(item, onEdit = { changed ->
                    val index = candidates.indexOfFirst { it.id == item.id }
                    if (index >= 0) candidates = candidates.toMutableList().also { it[index] = changed }
                }, onAnalyze = {
                    scope.launch {
                        busy = true
                        status = "Recupero dati per ${item.home}…"
                        val scored = analyzeCandidate(item)
                        val index = candidates.indexOfFirst { it.id == item.id }
                        if (index >= 0) candidates = candidates.toMutableList().also { it[index] = scored }
                        status = "Analisi completata."
                        busy = false
                    }
                }, onSave = { onSaved(item) })
            }
        }
    }
}

@Composable
private fun CompactFilterField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.height(48.dp))
}

@Composable
fun CandidateCard(item: MatchCandidate, onEdit: (MatchCandidate) -> Unit, onAnalyze: () -> Unit, onSave: () -> Unit) {
    var home by remember(item.id) { mutableStateOf(item.home) }
    var away by remember(item.id) { mutableStateOf(item.away) }
    var o1 by remember(item.id) { mutableStateOf(item.odd1.toString()) }
    var ox by remember(item.id) { mutableStateOf(item.oddX.toString()) }
    var o2 by remember(item.id) { mutableStateOf(item.odd2.toString()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactTextField("Casa", home, Modifier.weight(1f)) { home = it; onEdit(item.copy(home = it)) }
                CompactTextField("Ospite", away, Modifier.weight(1f)) { away = it; onEdit(item.copy(away = it)) }
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OddsField("1", o1, Modifier.weight(1f)) { o1 = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(odd1 = v)) } }
                OddsField("X", ox, Modifier.weight(1f)) { ox = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(oddX = v)) } }
                OddsField("2", o2, Modifier.weight(1f)) { o2 = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> onEdit(item.copy(odd2 = v)) } }
            }
            if (item.verdict != Verdict.NEEDS_DATA) {
                Spacer(Modifier.height(2.dp))
                Text("${item.verdict} · Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · P ${(item.estimatedP * 100).fmt1()}% · Fair ${item.fairOdd.fmt2()} · Cl. ${item.homeRank ?: "?"}/${item.awayRank ?: "?"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.labelSmall, maxLines = 3)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onAnalyze, modifier = Modifier.weight(1f).height(32.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("ANALIZZA", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(32.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("SALVA", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun CompactTextField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium, modifier = modifier.height(56.dp))
}

@Composable
fun OddsField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.bodyMedium, modifier = modifier.height(56.dp))
}

@Composable
fun HistoryScreen(history: List<MatchCandidate>, onChange: (List<MatchCandidate>) -> Unit) {
    val settled = history.filter { it.result != BetResult.PENDING }
    val won = settled.count { it.result == BetResult.WON }
    val staked = settled.size.toDouble()
    val profit = settled.sumOf { if (it.result == BetResult.WON) it.odd1 - 1.0 else -1.0 }
    val roi = if (staked == 0.0) 0.0 else profit / staked
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Casi: ${settled.size} · Vinte: $won · Win ${(if (settled.isEmpty()) 0.0 else won * 100.0 / settled.size).fmt1()}% · ROI ${(roi * 100).fmt1()}%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text("Stake virtuale 1 unità. Gli esiti alimentano la calibrazione.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(history.reversed(), key = { _, it -> it.id }) { reverseIndex, item ->
                val realIndex = history.lastIndex - reverseIndex
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(6.dp)) {
                        Text("${item.home} – ${item.away} · 1 @${item.odd1.fmt2()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · ${item.verdict}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.WON) }) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("VINTA", style = MaterialTheme.typography.labelSmall) }
                            Button(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.LOST) }) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("PERSA", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(onClick = { onChange(history.toMutableList().also { it[realIndex] = item.copy(result = BetResult.PENDING) }) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("PEND.", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataScreen() {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Valutazione", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("• classifica generale\n• rendimento della squadra di casa in casa\n• fragilità dell'ospite in trasferta\n• punti nelle ultime 5\n• probabilità 1 normalizzata per overround\n• confronto quota / probabilità reale (EV e fair odd)\n• bonus rimbalzo solo se supportato dai dati\n• PRUDENTE: score ≥70, EV ≥3%, edge ≥1,5%\n• FORTE: score ≥80, EV ≥6%, edge ≥2,5%\n• se mancano dati basilari: DATI INSUFFICIENTI", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text("Fonti dati", fontWeight = FontWeight.Bold)
        Text("Diretta è la fonte primaria; SofaScore completa i dati quando necessario. L'app non richiede né utilizza una API key per le valutazioni.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun Double.fmt1() = String.format(Locale.US, "%.1f", this)
private fun Double.fmt2() = String.format(Locale.US, "%.2f", this)
