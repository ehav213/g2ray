from pathlib import Path
import re

root = Path('.')
activity_path = root / 'app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt'
activity = activity_path.read_text(encoding='utf-8')
pattern = r'    private fun ocr\(uri: Uri\) \{.*?(?=\n    private fun handlePfrOcr\(result: Text\) \{)'
match = re.search(pattern, activity, re.S)
if not match:
    raise SystemExit('PFR OCR function block not found')
restored = '    private fun ocr(uri: Uri) {\n        if (photoMode == PhotoMode.PFR) {\n            clearResults()\n            ocrContainer.removeAllViews()\n            ocrContainer.visibility = View.VISIBLE\n            addOcrLabel("PFR OCR • READING CONFIRMED CROP")\n            addOcrInfo("Previous manual entry fields were cleared. Only values detected from this cropped PFR image will be populated.")\n        }\n        status("OCR • READING IMAGE…")\n        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n        runCatching { InputImage.fromFilePath(this, uri) }\n            .onSuccess { image ->\n                recognizer.process(image)\n                    .addOnSuccessListener { result ->\n                        if (photoMode == PhotoMode.PFR) handlePfrOcr(result)\n                        else handleOcrText(result.text.trim())\n                    }\n                    .addOnFailureListener { e -> status("OCR FAILED • CHECK LOG"); appLog.e("OCR_FAIL", e) }\n                    .addOnCompleteListener { recognizer.close() }\n            }\n            .onFailure { e -> recognizer.close(); appLog.e("OCR_IMAGE_FAIL", e) }\n    }'
activity = activity[:match.start()] + restored + activity[match.end():]
activity_path.write_text(activity, encoding='utf-8')

# Advance version only; all 1.4.6 AWM/IPC and 1.4.7 launcher-icon behavior remains.
gradle_path = root / 'app/build.gradle.kts'
gradle = gradle_path.read_text(encoding='utf-8')
if 'versionCode = 47' not in gradle or 'versionName = "1.4.7"' not in gradle:
    raise SystemExit('Expected 1.4.7 version metadata not found')
gradle = gradle.replace('versionCode = 47', 'versionCode = 48', 1)
gradle = gradle.replace('versionName = "1.4.7"', 'versionName = "1.4.8"', 1)
gradle_path.write_text(gradle, encoding='utf-8')

engine_path = root / 'app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt'
engine = engine_path.read_text(encoding='utf-8')
old_banner = 'LINE ASSISTANT 1.4.7 • STRAIGHTENED OCR + VERIFIED AWM/IPC TRACE'
new_banner = 'LINE ASSISTANT 1.4.8 • RESTORED 1.4.5 PFR OCR + VERIFIED AWM/IPC TRACE'
if old_banner not in engine:
    raise SystemExit('Expected 1.4.7 banner not found')
engine = engine.replace(old_banner, new_banner, 1)
engine_path.write_text(engine, encoding='utf-8')

# Guard against accidental reintroduction of the 1.4.6 multi-pass OCR into the PFR path.
activity_check = activity_path.read_text(encoding='utf-8')
ocr_block = re.search(pattern, activity_check, re.S).group(0)
if 'InputImage.fromFilePath(this, uri)' not in ocr_block:
    raise SystemExit('Restored OCR does not read the crop output directly')
if 'PfrImagePreprocessor' in ocr_block or 'runPfrOcrPipeline' in ocr_block:
    raise SystemExit('Multi-pass OCR regression still present in active PFR OCR block')

print('Line Assistant 1.4.8: exact 1.4.5 PFR OCR path restored; 1.4.7 icon and 1.4.6 AWM/IPC retained')
