from pathlib import Path

path = Path('app/src/main/res/layout/activity_line_assistant.xml')
s = path.read_text()
old = 'android:text="Settings & Data"'
new = 'android:text="Settings &amp; Data"'
if old not in s:
    raise SystemExit('1.4.2 XML fix: Settings & Data anchor not found')
s = s.replace(old, new, 1)
path.write_text(s)
print('Line Assistant 1.4.2 XML entity fix applied')
