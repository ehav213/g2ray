from pathlib import Path

# SourceRangeResolver.kt is updated in the branch itself. This build-time patch only bumps
# the installed application version after the existing 1.3.3 patch has run.
g = Path('app/build.gradle.kts')
s = g.read_text()
if 'versionCode = 31' not in s or 'versionName = "1.3.3"' not in s:
    raise SystemExit('1.3.4 patch: expected 1.3.3 build metadata not found')
s = s.replace('versionCode = 31', 'versionCode = 32', 1)
s = s.replace('versionName = "1.3.3"', 'versionName = "1.3.4"', 1)
g.write_text(s)
print('Line Assistant 1.3.4 complete TSM/AMM task-range build metadata applied')
