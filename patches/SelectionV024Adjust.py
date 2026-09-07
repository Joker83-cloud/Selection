from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

# Data tab now receives the live learning history and quota range.
s = s.replace('2 -> DataScreen()', '2 -> DataScreen(history, minOdd, maxOdd)', 1)
s = s.replace('1 -> HistoryScreen(history) { updated -> history = updated; store.save(updated) }',
              '1 -> HistoryScreen(history, minOdd, maxOdd) { updated -> history = updated; store.save(updated) }', 1)

# Add the weighted-history pass after source enrichment.
old = '''                HybridFootballClient("").enrich(item, date)'''
new = '''                val base = HybridFootballClient("").enrich(item, date)
                WeightedHistoryEngine.enrich(base)'''
s = s.replace(old, new, 1)

# Replace STORICO + DATI with final adaptive controls.
start = s.index('@Composable\nfun HistoryScreen(')
end = s.index('private fun Double.fmt1()', start)
replacement = r'''@Composable
fun HistoryScreen(
    history: List<MatchCandidate>,
    minOdd: Double,
    maxOdd: Double,
    onChange: (List<MatchCandidate>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settled = history.filter { it.result != BetResult.PENDING }
    val won = settled.count { it.result == BetResult.WON }
    val staked = settled.size.toDouble()
    val profit = settled.sumOf { if (it.result == BetResult.WON) it.odd1 - 1.0 else -1.0 }
    val roi = if (staked == 0.0) 0.0 else profit / staked
    var correctionMode by remember { mutableStateOf(false) }
    var correctionIndex by remember(history.size) { mutableIntStateOf(history.lastIndex.coerceAtLeast(0)) }

    fun copyReport() {
        val report = buildSelectionReport(history, minOdd, maxOdd)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OneStake Selection AI Report", report))
        android.widget.Toast.makeText(context, "REPORT copiato: incollalo in ChatGPT", android.widget.Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Casi: ${settled.size} · Vinte: $won · Win ${(if (settled.isEmpty()) 0.0 else won * 100.0 / settled.size).fmt1()}% · ROI ${(roi * 100).fmt1()}%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text("Gli esiti alimentano la calibrazione; la forma recente è pesata per qualità avversario, campo e margine.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = ::copyReport, modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(vertical = 0.dp)) {
                Text("📋 REPORT", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = {
                correctionMode = !correctionMode
                if (history.isNotEmpty()) correctionIndex = history.lastIndex
            }, modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(vertical = 0.dp)) {
                Text(if (correctionMode) "CHIUDI CORREZIONE" else "↔ CORREGGI", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (correctionMode && history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val item = history[correctionIndex.coerceIn(0, history.lastIndex)]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(7.dp)) {
                    Text("CORREZIONE ${correctionIndex + 1}/${history.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("${item.home} – ${item.away} · 1 @${item.odd1.fmt2()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text("Esito attuale: ${item.result} · Score ${item.score} · P ${(item.estimatedP * 100).fmt1()}%", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { if (correctionIndex > 0) correctionIndex-- }, enabled = correctionIndex > 0,
                            modifier = Modifier.weight(1f).height(32.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("◀ INDIETRO", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { if (correctionIndex < history.lastIndex) correctionIndex++ }, enabled = correctionIndex < history.lastIndex,
                            modifier = Modifier.weight(1f).height(32.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("AVANTI ▶", style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { onChange(history.toMutableList().also { it[correctionIndex] = item.copy(result = BetResult.WON) }) }, modifier = Modifier.weight(1f).height(30.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("VINTA", style = MaterialTheme.typography.labelSmall) }
                        Button(onClick = { onChange(history.toMutableList().also { it[correctionIndex] = item.copy(result = BetResult.LOST) }) }, modifier = Modifier.weight(1f).height(30.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("PERSA", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(onClick = { onChange(history.toMutableList().also { it[correctionIndex] = item.copy(result = BetResult.PENDING) }) }, modifier = Modifier.weight(1f).height(30.dp), contentPadding = PaddingValues(vertical = 0.dp)) { Text("PEND.", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(history.reversed(), key = { _, it -> it.id }) { reverseIndex, item ->
                val realIndex = history.lastIndex - reverseIndex
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(6.dp)) {
                        Text("${item.home} – ${item.away} · 1 @${item.odd1.fmt2()}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("Score ${item.score} · EV ${(item.ev * 100).fmt1()}% · P ${(item.estimatedP * 100).fmt1()}% · ${item.verdict} · ${item.result}", style = MaterialTheme.typography.labelSmall)
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
fun DataScreen(history: List<MatchCandidate>, minOdd: Double, maxOdd: Double) {
    val settled = history.filter { it.result != BetResult.PENDING }
    val won = settled.count { it.result == BetResult.WON }
    val calibrationState = when {
        settled.size < 20 -> "RACCOLTA DATI (${settled.size}/20 minimo)"
        settled.size < 50 -> "CALIBRAZIONE INIZIALE"
        settled.size < 100 -> "CALIBRAZIONE ATTIVA"
        else -> "CALIBRAZIONE MATURA"
    }
    val strong = settled.filter { it.verdict == Verdict.STRONG }
    val prudent = settled.filter { it.verdict == Verdict.PRUDENT }
    val strongWr = if (strong.isEmpty()) 0.0 else strong.count { it.result == BetResult.WON } * 100.0 / strong.size
    val prudentWr = if (prudent.isEmpty()) 0.0 else prudent.count { it.result == BetResult.WON } * 100.0 / prudent.size

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Motore adattivo 1X2", fontWeight = FontWeight.Bold)
        Text("Filtro corrente quota 1: ${minOdd.fmt2()}–${maxOdd.fmt2()}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text("Stato: $calibrationState", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text("Esiti registrati: ${settled.size} · vinte: $won · win rate ${(if (settled.isEmpty()) 0.0 else won * 100.0 / settled.size).fmt1()}%", style = MaterialTheme.typography.bodySmall)
        Text("FORTE: ${strong.size} casi · WR ${strongWr.fmt1()}% · PRUDENTE: ${prudent.size} casi · WR ${prudentWr.fmt1()}%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("Come valuta", fontWeight = FontWeight.Bold)
        Text("• quota 1-X-2 e probabilità di mercato normalizzata\n• classifica e forza relativa\n• rendimento casa / trasferta\n• ultime partite PESATE: avversario forte/debole, casa/trasferta, margine del risultato\n• una vittoria difficile vale più di una vittoria attesa; una sconfitta difficile pesa meno\n• confronto probabilità stimata / quota (EV e fair odd)\n• calibrazione progressiva dagli esiti reali, senza cambiare modello per pochi casi isolati", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("Obiettivo", fontWeight = FontWeight.Bold)
        Text("Solo mercato 1 fisso nel range scelto. Il motore deve imparare quali profili di partita funzionano meglio e correggere gradualmente la probabilità stimata.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("Fonti", fontWeight = FontWeight.Bold)
        Text("Diretta primaria; SofaScore completa dati strutturati e forma pesata quando necessario.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun buildSelectionReport(history: List<MatchCandidate>, minOdd: Double, maxOdd: Double): String {
    val settled = history.filter { it.result != BetResult.PENDING }
    val won = settled.count { it.result == BetResult.WON }
    val profit = settled.sumOf { if (it.result == BetResult.WON) it.odd1 - 1.0 else -1.0 }
    val roi = if (settled.isEmpty()) 0.0 else profit / settled.size
    val sb = StringBuilder()
    sb.appendLine("ONESTAKE · SELECTION AI · REPORT COMPLETO")
    sb.appendLine("Mercato: 1 fisso")
    sb.appendLine("Range quota 1: ${minOdd.fmt2()}–${maxOdd.fmt2()}")
    sb.appendLine("Casi salvati: ${history.size}")
    sb.appendLine("Esiti conclusi: ${settled.size} · vinte: $won · perse: ${settled.size - won}")
    sb.appendLine("Win rate: ${(if (settled.isEmpty()) 0.0 else won * 100.0 / settled.size).fmt1()}% · ROI virtuale: ${(roi * 100).fmt1()}%")
    sb.appendLine("Calibrazione: ${if (settled.size >= 20) "ATTIVA" else "IN RACCOLTA (${settled.size}/20)"}")
    sb.appendLine("Forma recente: PESATA per forza avversario, campo e margine risultato")
    sb.appendLine()
    sb.appendLine("STORICO (più recente prima)")
    history.asReversed().take(80).forEachIndexed { i, x ->
        sb.appendLine("${i + 1}. ${x.home} - ${x.away} | 1 @${x.odd1.fmt2()} | ${x.result} | ${x.verdict} | Score ${x.score} | P ${(x.estimatedP * 100).fmt1()}% | EV ${(x.ev * 100).fmt1()}% | Cl ${x.homeRank ?: "?"}/${x.awayRank ?: "?"} | Casa ${(x.homeHomeWinRate?.times(100))?.fmt1() ?: "?"}% | LossFuori ${(x.awayAwayLossRate?.times(100))?.fmt1() ?: "?"}% | FormaPesata ${x.homeFormPoints5 ?: "?"}/${x.awayFormPoints5 ?: "?"}")
        if (x.notes.isNotBlank()) sb.appendLine("   Note: ${x.notes}")
    }
    return sb.toString()
}

'''
s = s[:start] + replacement + s[end:]
p.write_text(s)
