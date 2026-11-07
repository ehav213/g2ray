from pathlib import Path

root = Path("app/src/main")
activity = root / "java/com/a340/technicalassistant/LineAssistantActivity.kt"
manifest = root / "AndroidManifest.xml"
paths = root / "res/xml/file_paths.xml"
gradle = Path("app/build.gradle.kts")
editor = root / "java/com/a340/technicalassistant/ImageEditorActivity.kt"
assert editor.exists(), "Image editor Kotlin source missing"
s = activity.read_text()
old = "    private fun ocr(uri: Uri) {"
assert s.count(old) == 1, f"Expected one OCR entrypoint; got {s.count(old)}"
s = s.replace(old, "    private fun ocrProcessed(uri: Uri) {", 1)
anchor = "    private val cameraPermission = registerForActivityResult"
assert s.count(anchor) == 1, "Camera callback not found"
s = s.replace(anchor, '''    // Intercept the existing camera/gallery/crop -> OCR boundary.
    // Keep all MEL/TSM routing and original capture behavior intact.
    private val imageEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ImageEditorActivity.EXTRA_RESULT)?.let {
                ocrProcessed(Uri.parse(it))
            }
        }
    }
    private fun ocr(uri: Uri) {
        imageEditor.launch(Intent(this, ImageEditorActivity::class.java)
            .putExtra(ImageEditorActivity.EXTRA_SOURCE, uri.toString()))
    }

''' + anchor, 1)
activity.write_text(s)
m = manifest.read_text()
assert "ImageEditorActivity" not in m
anchor = '        <activity android:name=".PdfPageActivity"'
assert anchor in m
m = m.replace(anchor, '        <activity android:name=".ImageEditorActivity" android:exported="false" />\n' + anchor, 1)
manifest.write_text(m)
p = paths.read_text()
assert "<paths" in p
if 'name="edited"' not in p:
    p = p.replace("</paths>", '    <cache-path name="edited" path="edited/" />\n</paths>')
paths.write_text(p)
g = gradle.read_text()
assert 'versionCode = 59' in g and 'versionName = "1.4.5-PC13-FSI-COMPLETE"' in g, "Wrong source version"
g = g.replace("versionCode = 59", "versionCode = 60", 1)
g = g.replace('versionName = "1.4.5-PC13-FSI-COMPLETE"', 'versionName = "1.4.5-PC14-IMAGE-EDITOR"', 1)
gradle.write_text(g)
assert "private fun ocrProcessed(uri: Uri)" in activity.read_text()
assert "private fun ocr(uri: Uri)" in activity.read_text()
assert "ImageEditorActivity" in manifest.read_text()
assert 'name="edited"' in paths.read_text()
print("PASS PC14 image editor integrated before OCR")
