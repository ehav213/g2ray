from pathlib import Path
import base64
import hashlib

root = Path('.')

# Rebuild the approved launcher artwork from lossless Base64 source chunks.
chunks = [root / 'icon147' / f'webp{i}.txt' for i in range(8)]
missing = [str(p) for p in chunks if not p.exists()]
if missing:
    raise SystemExit(f'Missing icon chunk(s): {missing}')

payload = ''.join(p.read_text(encoding='utf-8').strip() for p in chunks)
icon_bytes = base64.b64decode(payload, validate=True)
expected_sha256 = '4eef8e4b49d20415af57873b3d04dde5972c88ff11f53880d9fcc575a16554b2'
actual_sha256 = hashlib.sha256(icon_bytes).hexdigest()
if actual_sha256 != expected_sha256:
    raise SystemExit(f'Launcher icon checksum mismatch: {actual_sha256}')

icon_path = root / 'app/src/main/res/drawable-nodpi/line_assistant_icon_exact.webp'
icon_path.parent.mkdir(parents=True, exist_ok=True)
icon_path.write_bytes(icon_bytes)

# Point both normal and round launcher icons at the approved raster artwork.
manifest_path = root / 'app/src/main/AndroidManifest.xml'
manifest = manifest_path.read_text(encoding='utf-8')
old_icon = '@drawable/line_assistant_icon'
new_icon = '@drawable/line_assistant_icon_exact'
if manifest.count(old_icon) < 2:
    raise SystemExit('Expected launcher icon references were not found in AndroidManifest.xml')
manifest = manifest.replace(old_icon, new_icon)
manifest_path.write_text(manifest, encoding='utf-8')

# Version only; keep all 1.4.6 functional behavior unchanged.
gradle_path = root / 'app/build.gradle.kts'
gradle = gradle_path.read_text(encoding='utf-8')
if 'versionCode = 46' not in gradle or 'versionName = "1.4.6"' not in gradle:
    raise SystemExit('Expected 1.4.6 version metadata not found')
gradle = gradle.replace('versionCode = 46', 'versionCode = 47', 1)
gradle = gradle.replace('versionName = "1.4.6"', 'versionName = "1.4.7"', 1)
gradle_path.write_text(gradle, encoding='utf-8')

# Keep the feature banner accurate while identifying the package version.
engine_path = root / 'app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt'
engine = engine_path.read_text(encoding='utf-8')
engine = engine.replace(
    'LINE ASSISTANT 1.4.6 • STRAIGHTENED OCR + VERIFIED AWM/IPC TRACE',
    'LINE ASSISTANT 1.4.7 • STRAIGHTENED OCR + VERIFIED AWM/IPC TRACE',
    1,
)
engine_path.write_text(engine, encoding='utf-8')

print(f'Line Assistant 1.4.7 exact launcher icon applied: sha256={actual_sha256}')
