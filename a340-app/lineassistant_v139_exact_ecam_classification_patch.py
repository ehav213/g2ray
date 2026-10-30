from pathlib import Path

# Line Assistant 1.3.9
# Fix: a PFR cockpit effect may be an ECAM alert even when the PFR row does not
# print an explicit EWD/ECAM type. Classify it as ECAM only when the cockpit
# message exactly matches an indexed MEL ECAM title (optionally chapter-scoped).
# Otherwise keep it in Cockpit Effect / Status. No fuzzy guessing.

engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()

anchor = '''    fun resolvePfr(fault: PostFlightAnalyzer.Fault, aircraftProfile: String): LineAssistantResult = resolve(\n'''
if anchor not in e:
    raise SystemExit('1.3.9: resolvePfr anchor not found')

helper = r'''    fun classifyCockpitEntryExact(message: String?, cockpitAta: String?): String? {
        val raw = message?.trim().orEmpty()
        if (raw.isBlank()) return null
        val wanted = normalizeText(raw)
        val chapter = cockpitAta?.take(2)?.filter(Char::isDigit).orEmpty()
        val sql: String
        val args: Array<String>?
        if (chapter.length == 2) {
            sql = "SELECT title FROM mel_entries WHERE ata_chapter=?"
            args = arrayOf(chapter)
        } else {
            sql = "SELECT title FROM mel_entries"
            args = null
        }
        val exactMel = runCatching {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    if (normalizeText(c.getString(0).orEmpty()) == wanted) return@use true
                }
                false
            }
        }.getOrDefault(false)
        return if (exactMel) "EWD WARNING(S)" else null
    }

'''
e = e.replace(anchor, helper + anchor, 1)
e = e.replace(
    'LINE ASSISTANT 1.3.8 • AIRBUS ENTRY POINT + EXACT TSM WORKFLOW',
    'LINE ASSISTANT 1.3.9 • EXACT ECAM/CMS ENTRY POINT + TSM WORKFLOW'
)
engine.write_text(e)

activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()
old = '''        val seedType = seed?.cockpitType.orEmpty()\n        val ecamSeed = if (isEcamCockpitType(seedType)) seed?.cockpitEffect.orEmpty() else ""\n        val statusSeed = if (ecamSeed.isBlank()) seed?.cockpitEffect.orEmpty() else ""\n'''
new = '''        val printedType = seed?.cockpitType.orEmpty()\n        val exactDbType = if (printedType.isBlank() && !seed?.cockpitEffect.isNullOrBlank()) {\n            engine?.classifyCockpitEntryExact(seed?.cockpitEffect, seed?.cockpitAta)\n        } else null\n        val seedType = printedType.ifBlank { exactDbType.orEmpty() }\n        val ecamSeed = if (isEcamCockpitType(seedType)) seed?.cockpitEffect.orEmpty() else ""\n        val statusSeed = if (ecamSeed.isBlank()) seed?.cockpitEffect.orEmpty() else ""\n'''
if old not in a:
    raise SystemExit('1.3.9: seed classification anchor not found')
a = a.replace(old, new, 1)
activity.write_text(a)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 36' not in g or 'versionName = "1.3.8"' not in g:
    raise SystemExit('1.3.9: expected 1.3.8 version metadata not found')
g = g.replace('versionCode = 36', 'versionCode = 37', 1)
g = g.replace('versionName = "1.3.8"', 'versionName = "1.3.9"', 1)
gradle.write_text(g)

print('Line Assistant 1.3.9 exact MEL-based ECAM classification applied')
