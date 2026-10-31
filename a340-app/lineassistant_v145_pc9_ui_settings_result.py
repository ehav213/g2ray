from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
XML = ROOT / "app/src/main/res/layout/activity_line_assistant.xml"
GRADLE = ROOT / "app/build.gradle.kts"

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

expected_before = {
    XML: "db771c7e27cb18c633d480d807095c0663f1860fdaf9499c16983d3a2c6cf661",
    GRADLE: "a7585f8e96a3e19b055c1c13c2a333c82f8f99520671c6a7be077e8f1f0586c9",
}
for path, expected in expected_before.items():
    actual = sha(path)
    if actual != expected:
        raise SystemExit(f"PC9 refuses non-PC8 input {path}: {actual} != {expected}")

xml = XML.read_text()
result_block = '''        <TextView android:id="@+id/txtResultHeading" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="22dp" android:text="Verified Line Maintenance Result" android:textColor="#1C1C1E" android:textSize="17sp" android:textStyle="bold" android:visibility="gone" />\n        <TextView android:id="@+id/txtResult" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:background="#FFFFFF" android:lineSpacingExtra="3dp" android:padding="16dp" android:textColor="#1C1C1E" android:textIsSelectable="true" android:textSize="13sp" android:visibility="gone" />\n'''
if xml.count(result_block) != 1:
    raise SystemExit("PC9 result block anchor missing or duplicated")
xml = xml.replace(result_block, "", 1)

settings_title = '''                <TextView\n                    android:layout_width="match_parent"\n                    android:layout_height="wrap_content"\n                    android:text="Settings &amp; Data"\n                    android:textColor="#1C1C1E"\n                    android:textSize="16sp"\n                    android:textStyle="bold" />\n'''
settings_result = settings_title + '''                <TextView android:id="@+id/txtResultHeading" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="14dp" android:text="Verified Line Maintenance Result" android:textColor="#1C1C1E" android:textSize="17sp" android:textStyle="bold" android:visibility="gone" />\n                <TextView android:id="@+id/txtResult" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp" android:background="#F2F2F7" android:lineSpacingExtra="3dp" android:padding="12dp" android:textColor="#1C1C1E" android:textIsSelectable="true" android:textSize="13sp" android:visibility="gone" />\n'''
if xml.count(settings_title) != 1:
    raise SystemExit("PC9 settings title anchor missing or duplicated")
xml = xml.replace(settings_title, settings_result, 1)

if xml.count('@+id/txtResultHeading') != 1 or xml.count('@+id/txtResult"') != 1:
    raise SystemExit("PC9 result IDs are not unique")
if xml.index('@+id/txtResultHeading') > xml.index('@+id/txtSourcesHeading'):
    raise SystemExit("PC9 result did not move into earlier Settings panel")
XML.write_text(xml)

gradle = GRADLE.read_text()
gradle = gradle.replace('versionCode = 53', 'versionCode = 54', 1)
gradle = gradle.replace('versionName = "1.4.5-PC8"', 'versionName = "1.4.5-PC9-UI"', 1)
GRADLE.write_text(gradle)

expected_after = {
    XML: "7ae9195f163d8395b62b2fe893dc7b8e9c771d6cf093d32230dc2e62341911db",
    GRADLE: "50209453434f664c886a27315e02f3cb68abd75ad32c4633eeaccf5c7cbb24e7",
}
for path, expected in expected_after.items():
    actual = sha(path)
    if actual != expected:
        raise SystemExit(f"PC9 output hash mismatch {path}: {actual} != {expected}")

print("Line Assistant 1.4.5-PC9-UI applied: Verified Line Maintenance Result moved to Settings only")
