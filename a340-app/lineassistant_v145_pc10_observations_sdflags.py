from pathlib import Path
import hashlib

ROOT = Path.cwd()
ENGINE = ROOT / "app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt"
GRADLE = ROOT / "app/build.gradle.kts"

def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()

before = {
    ENGINE: "9d1ad7419a586b0072ff204e9a148118d327e40de773bb0a4b501ee067ad6a0d",
    GRADLE: "50209453434f664c886a27315e02f3cb68abd75ad32c4633eeaccf5c7cbb24e7",
}
for p, expected in before.items():
    actual = sha(p)
    if actual != expected:
        raise SystemExit(f"PC10 refuses non-PC9 baseline {p}: {actual} != {expected}")

s = ENGINE.read_text()
old = '''    private fun parseTsmIndexRecords(filename: String, page: Int, content: String): List<TsmIndexRecord> =\n        TsmSymptomIndexParser.parse(content).map { r ->\n            TsmIndexRecord(\n                indexFilename = filename,\n                indexPage = page,\n                indexTaskRef = r.taskRef,\n                cockpitAta = r.cockpitAta,\n                cockpitMessage = r.cockpitMessage,\n                cockpitType = r.cockpitType,\n                maintenanceAta = r.maintenanceAta,\n                maintenanceMessage = r.maintenanceMessage,\n                source = r.source,\n                faultClass = r.faultClass,\n                identifiers = r.identifiers,\n                hasFaultMessage = r.hasFaultMessage\n            )\n        }\n'''
new = '''    private fun parseTsmIndexRecords(filename: String, page: Int, content: String): List<TsmIndexRecord> =\n        (TsmSymptomIndexParser.parse(content) + TsmCockpitStatusIndexParser.parse(content))\n            .distinctBy { listOf(it.taskRef, it.cockpitAta, it.cockpitMessage, it.cockpitType, it.maintenanceAta, it.maintenanceMessage, it.source, it.faultClass).joinToString("§") }\n            .map { r ->\n                TsmIndexRecord(\n                    indexFilename = filename,\n                    indexPage = page,\n                    indexTaskRef = r.taskRef,\n                    cockpitAta = r.cockpitAta,\n                    cockpitMessage = r.cockpitMessage,\n                    cockpitType = r.cockpitType,\n                    maintenanceAta = r.maintenanceAta,\n                    maintenanceMessage = r.maintenanceMessage,\n                    source = r.source,\n                    faultClass = r.faultClass,\n                    identifiers = r.identifiers,\n                    hasFaultMessage = r.hasFaultMessage\n                )\n            }\n'''
if old not in s:
    raise SystemExit("PC10 parseTsmIndexRecords anchor missing")
ENGINE.write_text(s.replace(old, new, 1))

g = GRADLE.read_text()
g = g.replace('versionCode = 54', 'versionCode = 55', 1)
g = g.replace('versionName = "1.4.5-PC9-UI"', 'versionName = "1.4.5-PC10-OBS-SDFLAG"', 1)
GRADLE.write_text(g)

after = {
    ENGINE: "c7da44b6e9fa42cc523f8ab130f086b1b5afbdb69ee9517bebab7f2675234b0e",
    GRADLE: "91d7654aaf48528974239681274ac0bc3e5a4ffa6f744b9c1308e61c7cb04f02",
}
for p, expected in after.items():
    actual = sha(p)
    if actual != expected:
        raise SystemExit(f"PC10 output hash mismatch {p}: {actual} != {expected}")

print("PC10 OBSERVATIONS / SD FLAG(S) sidecar routing applied; core TSM parser unchanged")
