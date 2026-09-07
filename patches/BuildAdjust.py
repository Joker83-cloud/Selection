from pathlib import Path
import re
import sys

main_path = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])

s = main_path.read_text()

# Preserve ANALIZZA contents while switching between tabs.
if "private object AnalyzeSession" not in s:
    marker = "class MainActivity : ComponentActivity() {"
    session = (
        "private object AnalyzeSession {\n"
        "    var candidates: List<MatchCandidate> = emptyList()\n"
        "    var status: String = \"Allega uno o più screenshot con squadre e quote 1-X-2.\"\n"
        "}\n\n"
    )
    s = s.replace(marker, session + marker, 1)

s = s.replace(
    "var candidates by remember { mutableStateOf(emptyList<MatchCandidate>()) }",
    "var candidates by remember { mutableStateOf(AnalyzeSession.candidates) }",
)
s = s.replace(
    "var status by remember { mutableStateOf(\"Allega uno o più screenshot con squadre e quote 1-X-2.\") }",
    "var status by remember { mutableStateOf(AnalyzeSession.status) }",
)

anchor = "    val context = androidx.compose.ui.platform.LocalContext.current\n\n    fun updateRange()"
if "AnalyzeSession.candidates = candidates" not in s:
    s = s.replace(
        anchor,
        "    val context = androidx.compose.ui.platform.LocalContext.current\n\n"
        "    SideEffect {\n"
        "        AnalyzeSession.candidates = candidates\n"
        "        AnalyzeSession.status = status\n"
        "    }\n\n"
        "    fun updateRange()",
        1,
    )

# Make the top Min/Max filter and APPLY button comfortable and aligned.
s = s.replace(
    "modifier = modifier.height(48.dp))",
    "modifier = modifier.height(56.dp))",
    1,
)
s = s.replace(
    "modifier = Modifier.height(48.dp)",
    "modifier = Modifier.height(56.dp)",
    1,
)

main_path.write_text(s)

# Force the custom launcher icon even if the original manifest has no icon attribute.
m = manifest_path.read_text()
app = re.search(r"<application\\b[^>]*>", m, flags=re.S)
if not app:
    raise SystemExit("application tag not found")
tag = app.group(0)
tag = re.sub(r"\\s+android:icon=\"[^\"]+\"", "", tag)
tag = re.sub(r"\\s+android:roundIcon=\"[^\"]+\"", "", tag)
tag = tag[:-1] + "\n        android:icon=\"@drawable/ic_onestake\"\n        android:roundIcon=\"@drawable/ic_onestake\">"
m = m[:app.start()] + tag + m[app.end():]
manifest_path.write_text(m)
