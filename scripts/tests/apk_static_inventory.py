#!/usr/bin/env python3
"""Bounded, offline static inventory of a user-supplied APK. Never executes its contents."""
import collections
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1])
expected = 'e0e0256e9c314d68cba3ec5b2362318f0bbd81efd38b13e19aab4c5abb30118e'
hash_ = hashlib.sha256()
with apk.open('rb') as f:
    for block in iter(lambda: f.read(1024 * 1024), b''):
        hash_.update(block)
if hash_.hexdigest() != expected:
    raise SystemExit('APK checksum mismatch')

# Narrow search terms: these are leads, not proof that a cheat feature is functional.
terms = {
    'combat': r'kill.?aura|trigger.?bot|auto.?clicker|aim.?assist|aimbot|critical.?hit|reach.?hack|hitbox',
    'movement': r'flight.?hack|fly.?hack|speed.?hack|no.?slow|no.?fall|anti.?knockback|velocity.?hack|air.?jump|long.?jump|step.?hack|jesus.?hack|phase.?hack',
    'visibility': r'x.?ray|wall.?hack|player.?esp|entity.?esp|chest.?esp|tracer|full.?bright',
    'automation': r'auto.?mine|auto.?break|auto.?place|auto.?armor|auto.?totem|auto.?heal|auto.?tool|scaffold|nuker|packet.?fly|packet.?mine',
    'network': r'blink.?hack|packet.?spoof|packet.?cancel|packet.?delay|disabler|no.?packet|fake.?lag',
    'client': r'apollon.?client|apollon.?menu|mod.?menu|client.?module',
}
patterns = {k: re.compile(v, re.I) for k, v in terms.items()}
interesting = re.compile('|'.join('(?:' + v + ')' for v in terms.values()), re.I)
printable = re.compile(rb'[\x20-\x7e]{4,200}')
entries = []
matches = {k: collections.defaultdict(set) for k in terms}
counts = collections.Counter()
with zipfile.ZipFile(apk) as archive:
    infos = archive.infolist()
    if len(infos) > 100000:
        raise SystemExit('APK has too many entries')
    for info in infos:
        name = info.filename
        if name.endswith(('.dex', '.so')) or name == 'AndroidManifest.xml':
            entries.append({'name': name, 'bytes': info.file_size})
        if not name.endswith(('.dex', '.so')):
            continue
        if info.file_size > 750_000_000:
            raise SystemExit('Refusing oversized archive member: ' + name)
        # Analyze each member as a stream. Carry an overlap for strings at block boundaries.
        with archive.open(info) as stream:
            tail = b''
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    buf, tail = tail, b''
                else:
                    buf, tail = tail + chunk, b''
                    end = len(buf)
                    while end > 0 and 32 <= buf[end - 1] <= 126 and len(buf) - end < 200:
                        end -= 1
                    buf, tail = buf[:end], buf[end:]
                for raw in printable.findall(buf):
                    if len(raw) > 120:
                        continue
                    text = raw.decode('ascii')
                    if not interesting.search(text):
                        continue
                    # Keep small identifier-like strings, not URLs, data blobs, or private keys.
                    if not re.fullmatch(r'[A-Za-z0-9_.$/:;<>+ -]{4,100}', text) or '://' in text:
                        continue
                    for category, pattern in patterns.items():
                        if pattern.search(text):
                            counts[(category, name)] += 1
                            if len(matches[category][name]) < 20:
                                matches[category][name].add(text)
                if not chunk:
                    break
report = {'sha256': expected, 'apk_bytes': apk.stat().st_size, 'entry_count': len(infos),
          'code_entries': entries, 'matches': {
              cat: [{'member': member, 'occurrences': counts[(cat, member)], 'examples': sorted(values)}
                    for member, values in sorted(sources.items())]
              for cat, sources in matches.items()}}
Path('apollon-static-report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
print('::notice title=APK inventory::' + json.dumps({'bytes': report['apk_bytes'], 'entry_count': len(infos), 'code_entries': entries}, separators=(',', ':')))
for category, data in report['matches'].items():
    print('::notice title=APK ' + category + '::' + json.dumps(data, separators=(',', ':')))
