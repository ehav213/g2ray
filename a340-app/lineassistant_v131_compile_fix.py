from pathlib import Path

path = Path('app/src/main/java/com/a340/technicalassistant/LineAssistantEngine.kt')
s = path.read_text()

# Python runtime-patch escaping can materialize Regex("\s+") as an invalid
# Kotlin string escape. Avoid regex entirely for whitespace removal.
bad = r'.replace(Regex("\s+"), "")'
replacement = '.filterNot { it.isWhitespace() }'
if bad not in s:
    raise SystemExit('1.3.1 compile fix: invalid whitespace Regex not found')
s = s.replace(bad, replacement, 1)
path.write_text(s)
print('Line Assistant 1.3.1 compile escape fixed')
