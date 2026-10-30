from pathlib import Path

# Line Assistant 1.4.3
# Fix CropImageActivity confirmation UI when the app theme is NoActionBar.
# The cropper places its USE/Crop action in an ActionBar menu, so give only
# CropImageActivity a dedicated light theme with a visible ActionBar.

manifest = Path('app/src/main/AndroidManifest.xml')
m = manifest.read_text()
activity_anchor = '''        <activity android:name=".LineAssistantActivity" android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>\n        </activity>\n\n'''
if activity_anchor not in m:
    raise SystemExit('1.4.3: LineAssistantActivity manifest anchor not found')
crop_activity = '''        <activity\n            android:name="com.canhub.cropper.CropImageActivity"\n            android:exported="false"\n            android:theme="@style/Theme.A340Cropper"\n            tools:replace="android:theme,android:exported" />\n\n'''
if 'android:name="com.canhub.cropper.CropImageActivity"' not in m:
    m = m.replace(activity_anchor, activity_anchor + crop_activity, 1)
manifest.write_text(m)

themes = Path('app/src/main/res/values/themes.xml')
t = themes.read_text()
if 'name="Theme.A340Cropper"' not in t:
    style = '''\n    <style name="Theme.A340Cropper" parent="Theme.MaterialComponents.Light">\n        <item name="colorPrimary">#FFFFFF</item>\n        <item name="colorPrimaryVariant">#FFFFFF</item>\n        <item name="colorOnPrimary">#0A84FF</item>\n        <item name="colorSecondary">#0A84FF</item>\n        <item name="android:fontFamily">sans</item>\n        <item name="android:windowBackground">#000000</item>\n        <item name="android:statusBarColor">#FFFFFF</item>\n        <item name="android:navigationBarColor">#FFFFFF</item>\n        <item name="android:windowLightStatusBar">true</item>\n        <item name="android:windowLightNavigationBar">true</item>\n        <item name="actionMenuTextColor">#0A84FF</item>\n    </style>\n'''
    if '</resources>' not in t:
        raise SystemExit('1.4.3: themes closing tag not found')
    t = t.replace('</resources>', style + '</resources>', 1)
themes.write_text(t)

# Keep the explicit title/action created by 1.4.2 and make it even clearer.
activity = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantActivity.kt')
a = activity.read_text()
a = a.replace(
    'activityTitle = if (photoMode == PhotoMode.PFR) "CROP PFR • TAP USE TO CONFIRM" else "CROP IMAGE • TAP USE",',
    'activityTitle = if (photoMode == PhotoMode.PFR) "Crop PFR — tap USE" else "Crop image — tap USE",',
    1
)
activity.write_text(a)

# Version metadata.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
if 'versionCode = 40' not in g or 'versionName = "1.4.2"' not in g:
    raise SystemExit('1.4.3: expected 1.4.2 version metadata not found')
g = g.replace('versionCode = 40', 'versionCode = 41', 1)
g = g.replace('versionName = "1.4.2"', 'versionName = "1.4.3"', 1)
gradle.write_text(g)

engine = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
e = engine.read_text()
e = e.replace(
    'LINE ASSISTANT 1.4.2 • UNIFIED IOS-STYLE FIRST ENTRY POINT',
    'LINE ASSISTANT 1.4.3 • UNIFIED FIRST ENTRY + VISIBLE CROP CONFIRM'
)
engine.write_text(e)

print('Line Assistant 1.4.3 visible crop confirmation toolbar patch applied')
