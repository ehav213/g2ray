from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
GRADLE = ROOT / 'app/build.gradle.kts'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

expected_before = 'a3a58a74eb91d9d3bab10d146737fd99991cd23aef7bb3be871e6d628f229c4c'
a = sha(GRADLE)
if a != expected_before:
    raise SystemExit(f'PC12 refuses non-PC11 baseline {GRADLE}: {a} != {expected_before}')

g = GRADLE.read_text()
g = g.replace('versionCode = 56', 'versionCode = 57', 1)
g = g.replace('versionName = "1.4.5-PC11-TYPE-SELECTOR"', 'versionName = "1.4.5-PC12-FULL-TYPE-ROUTING"', 1)
GRADLE.write_text(g)

expected_after = '0d554d35c2f69781d2ed3ba7d70be1e435718412784801077e866dbd4ed4e0bc'
a = sha(GRADLE)
if a != expected_after:
    raise SystemExit(f'PC12 output hash mismatch {GRADLE}: {a} != {expected_after}')

print('PC12 full blank-ATA Type routing version applied; PC11 technical core unchanged')
