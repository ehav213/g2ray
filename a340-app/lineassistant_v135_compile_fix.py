from pathlib import Path

# Repair two mechanical code-generation issues in the 1.3.5 patch.
p = Path('app/src/main/java/com/a340/technicalassistant/PfrGeometryAnalyzerV2.kt')
s = p.read_text()
needle = 'Regex("\\s+")'
replacement = 'Regex("\\\\s+")'
count = s.count(needle)
if count < 2:
    raise SystemExit(f'1.3.5 compile fix: expected at least 2 invalid \\s regexes, found {count}')
s = s.replace(needle, replacement)
p.write_text(s)

e = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
t = e.read_text()
count_id = t.count('task.id')
if count_id < 2:
    raise SystemExit(f'1.3.5 compile fix: expected task.id references, found {count_id}')
t = t.replace('task.id', 'task.taskId')
e.write_text(t)

print(f'Line Assistant 1.3.5 compile fix applied: regex={count}, taskId={count_id}')
