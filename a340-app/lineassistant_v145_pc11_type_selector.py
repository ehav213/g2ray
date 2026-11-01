from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
ACT = ROOT / 'app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt'
GRADLE = ROOT / 'app/build.gradle.kts'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

expected_before = {
    ACT: 'dd24ab667ab83258e4deb966efa1f320d8ea136c66f7460649fe2169fb8361dc',
    GRADLE: '91d7654aaf48528974239681274ac0bc3e5a4ffa6f744b9c1308e61c7cb04f02',
}
for p,e in expected_before.items():
    a=sha(p)
    if a != e: raise SystemExit(f'PC11 refuses non-PC10 baseline {p}: {a} != {e}')

s=ACT.read_text()
old='val cockpitType = pfrField(cockpit, "TYPE", seedType, "e.g. MAINTENANCE STATUS")'
new='val cockpitType = CockpitTypeSelector.create(this, cockpit, seedType)'
if old not in s: raise SystemExit('PC11 TYPE field anchor not found')
ACT.write_text(s.replace(old,new,1))

g=GRADLE.read_text()
g=g.replace('versionCode = 55','versionCode = 56',1)
g=g.replace('versionName = "1.4.5-PC10-OBS-SDFLAG"','versionName = "1.4.5-PC11-TYPE-SELECTOR"',1)
GRADLE.write_text(g)

expected_after = {
    ACT: '903565168f4f653a1846235928cc5eb2654e2450c33aa73852855d33843e9eaa',
    GRADLE: 'a3a58a74eb91d9d3bab10d146737fd99991cd23aef7bb3be871e6d628f229c4c',
}
for p,e in expected_after.items():
    a=sha(p)
    if a != e: raise SystemExit(f'PC11 output hash mismatch {p}: {a} != {e}')
print('PC11 TYPE selector applied; routing/TSM core unchanged')
