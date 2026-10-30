from pathlib import Path

p = Path('lineassistant_v138_entrypoint_classification_patch.py')
s = p.read_text()
old = '''needle = \'\'\'                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\\n                        flightPhaseCode = phaseCode\\n                    )\'\'\'\nreplacement = \'\'\'                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\\n                        flightPhaseCode = phaseCode,\\n                        cockpitType = cockpitType\\n                    )\'\'\'\ncount = s.count(needle)\nif count != 2:\n    raise SystemExit(f\'1.3.8: expected 2 parser Fault constructors, found {count}\')\ns = s.replace(needle, replacement)\n'''
new = '''needle = \'\'\'                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\\n                        flightPhaseCode = phaseCode\\n                    )\'\'\'\nreplacement = \'\'\'                        cockpitAta = cockpitAtaDigits?.let(::formatAta),\\n                        flightPhaseCode = phaseCode,\\n                        cockpitType = cockpitType\\n                    )\'\'\'\nif s.count(needle) != 1:\n    raise SystemExit(\'1.3.8: CMS parser Fault constructor anchor not found\')\ns = s.replace(needle, replacement, 1)\n\nneedle = \'\'\'                    cockpitAta = formatAta(cockpitAtaDigits),\\n                    flightPhaseCode = phaseCode\\n                )\'\'\'\nreplacement = \'\'\'                    cockpitAta = formatAta(cockpitAtaDigits),\\n                    flightPhaseCode = phaseCode,\\n                    cockpitType = cockpitType\\n                )\'\'\'\nif s.count(needle) != 1:\n    raise SystemExit(\'1.3.8: cockpit-only parser Fault constructor anchor not found\')\ns = s.replace(needle, replacement, 1)\n'''
if old not in s:
    raise SystemExit('1.3.8 patchfix: original constructor matcher not found')
p.write_text(s.replace(old, new, 1))
print('Line Assistant 1.3.8 parser constructor patch matcher fixed')
