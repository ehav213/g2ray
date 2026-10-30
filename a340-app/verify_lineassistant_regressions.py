from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
engine = (ROOT / 'app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt').read_text()
activity = (ROOT / 'app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt').read_text()
post = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PostFlightAnalyzer.kt').read_text()
geom = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt').read_text()
ocrpipe = (ROOT / 'app/src/main/java/com/a340/technicalassistant/PfrOcrPipeline.kt').read_text()
repo = (ROOT / 'app/src/main/java/com/a340/technicalassistant/SearchRepository.kt').read_text()
gradle = (ROOT / 'app/build.gradle.kts').read_text()

checks = []
def require(name, condition):
    checks.append((name, bool(condition)))

# Build/source invariants.
require('version is 1.5.1', 'versionName = "1.5.1"' in gradle and 'versionCode = 51' in gradle)
require('SearchRepository uses bundled FTS SQLite directly', 'FtsSQLiteDatabase as SQLiteDatabase' in repo and 'android.database.sqlite.SQLiteDatabase' not in repo)
require('engine is 1.5.1 release', 'LINE ASSISTANT 1.5.1 • OCR + VERIFIED SUPPORT SOURCES' in engine)

# Regression: cockpit-only must never be re-labeled as CMS maintenance text.
require('cockpit-only PFR keeps maintenanceMessage empty', 'maintenanceMessage = if (hasCms) fault.text else ""' in engine)
require('cockpit-only PFR strips CMS metadata', 'maintenanceAta = if (hasCms)' in engine and 'identifiers = if (hasCms)' in engine)

# Regression: CRG VENT / STS-MAINTENANCE style index entries.
require('STS-MAINTENANCE parser support', '"STS-MAINTENANCE"' in engine)
require('STS-MAINTENANCE canonical equivalence', 'compact.contains("STSMAINTENANCE")' in engine)
require('two-stage cockpit candidate resolution', 'findCockpitTsmCandidates(input)' in engine and 'LineTsmState.NEEDS_CMS' in engine)
require('ambiguous CMS remains unresolved', 'LineTsmState.AMBIGUOUS' in engine and 'chooseUniqueIndexRecord' in engine)

# Regression: never fabricate CMS by clicking a TSM candidate.
require('candidate UI cannot fabricate CMS', 'USE THIS CMS FAULT' not in activity and 'Index candidate only — not copied into aircraft CMS input.' in activity)
require('actual CMS entry path is explicit', 'ENTER ACTUAL CMS FROM AIRCRAFT / PFR' in activity)

# Regression: suppress broad/guessing fallbacks.
resolve_block = engine[engine.index('    private fun resolve(input: FaultInput'):engine.index('    private fun isCockpitOnlyInput', engine.index('    private fun resolve(input: FaultInput'))]
mel_block = engine[engine.index('    private fun resolveMel('):engine.index('    private fun findCompatibleMelEntries', engine.index('    private fun resolveMel('))]
require('direct TSM fallback is not in resolver', 'directTask' not in resolve_block and 'resolveDirectTask' not in resolve_block)
require('equipment MEL fallback is not in resolver', 'findEquipmentMelItem' not in mel_block)
require('not-resolved result explicitly suppresses guessing', 'direct-task guessing are suppressed' in engine)

# Regression: support manuals only from verified references/identifiers.
support = engine[engine.index('    private fun supportHits('):engine.index('    private fun taskHit(', engine.index('    private fun supportHits('))]
require('supportHits does not derive identifiers from message text', 'extractIdentifiers(input.maintenanceMessage)' not in support)
require('supportHits derives exact IDs from selected TSM branch only', 'extractIdentifiers(scoped)' in support and 'extractReferencedPartNumbers(scoped)' in support)
require('AWM IPC identifiers use normalized exact matching', 'REPLACE(REPLACE(REPLACE(REPLACE(UPPER(i.token)' in engine and 'arrayOf(raw, raw, normalized, normalized)' in engine)

# Regression: stale UI state and OCR handling.
require('source heading reset', activity.count('txtSourcesHeading.text = "VERIFIED ORIGINAL SOURCES"') >= 2)
require('unified entry clears previous result', re.search(r'private fun showUnifiedFirstEntryPoint\(\).*?clearResults\(\)', activity, re.S) is not None)
require('PFR OCR clears prior free-text query', re.search(r'private fun handlePfrOcr\(result: Text, processingNote: String = ""\).*?editQuery\.setText\(""\)', activity, re.S) is not None)
require('PFR OCR uses confirmed crop enhancement pipeline', 'PfrOcrPipeline.recognize(this, uri)' in activity and 'estimateSkewDegrees' in ocrpipe and 'enhance(source: Bitmap' in ocrpipe)
require('crop supports fine straighten angle', 'rotationDegrees = 1' in activity and 'allowCounterRotation = true' in activity and 'Bitmap.CompressFormat.PNG' in activity)
require('AWM and IPC unresolved stages remain visible without guessing', 'unresolvedSourceCard(' in activity and 'Broad ATA/wiring guessing is suppressed.' in activity and 'Broad parts guessing is suppressed.' in activity)
require('cockpit-only crop parser exists', 'analyzeCockpitOnlyCrop' in geom)
require('PostFlight fault carries cockpitType', 'val cockpitType: String? = null' in post)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ' - ' + name)
if failed:
    raise SystemExit('\nRegression guard failed: ' + '; '.join(failed))
print(f'All {len(checks)} Line Assistant regression guards passed.')
