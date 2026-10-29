from pathlib import Path

path = Path('app/build.gradle.kts')
s = path.read_text()

if 'versionCode = 29' not in s or 'versionName = "1.3.1"' not in s:
    raise SystemExit('1.3.2 version patch: expected 1.3.1 build metadata not found')

s = s.replace('versionCode = 29', 'versionCode = 30', 1)
s = s.replace('versionName = "1.3.1"', 'versionName = "1.3.2"', 1)
path.write_text(s)
print('Line Assistant 1.3.2 cropped-PFR-row build metadata applied')
