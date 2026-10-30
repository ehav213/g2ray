from pathlib import Path

# Line Assistant 1.4.1 compile compatibility fix.
# Newer Activity/contract types brought in by the cropper require a non-null Uri.
# All legacy camera launchers already assign photoUri immediately before launch;
# this preserves behavior while preventing a nullable Uri from reaching TakePicture.

root = Path('app/src/main/java/com/a340/technicalassistant')
changed = []
for path in root.glob('*.kt'):
    text = path.read_text()
    old = 'takePhoto.launch(photoUri)'
    if old in text:
        count = text.count(old)
        text = text.replace(old, 'photoUri?.let { takePhoto.launch(it) }')
        path.write_text(text)
        changed.append((path.name, count))

if not changed:
    raise SystemExit('1.4.1 compile fix: no nullable camera launchers found')

print('Line Assistant 1.4.1 nullable camera Uri compile fix applied:', changed)
