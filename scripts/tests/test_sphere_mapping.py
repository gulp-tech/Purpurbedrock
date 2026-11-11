#!/usr/bin/env python3
"""Ensure the example and synthetic-client fixture use the same guarded sphere definitions."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
example = json.loads((root / 'docs/examples/spheres-pdc-mapping.json').read_text(encoding='utf-8'))
fixture = json.loads((root / 'purpurbedrock-completions/tests/bedrock/fixture-sphere-mapping.json').read_text(encoding='utf-8'))
assert example['format_version'] == fixture['format_version'] == 2
entries = example['items']['minecraft:paper']
assert len(entries) == 4
assert all(entry in fixture['items']['minecraft:paper'] for entry in entries)
assert {entry['model'] for entry in entries} == {f'voidspheres:sphere{i}' for i in range(1, 5)}
for entry in entries:
    suffix = entry['model'].split(':', 1)[1]
    assert entry['bedrock_identifier'] == f'geyser_custom:{suffix}'
    assert entry['bedrock_options']['icon'] == suffix
    assert entry['predicate'] == {
        'type': 'server_pdc_string', 'key': 'voidtalismans:talisman_id',
        'fallback_key': 'faraltalismans:talisman_id', 'value': suffix,
    }
    assert entry.get('predicate_strategy', 'and').lower() == 'and'
print('Sphere mappings: four guarded definitions agree with the test fixture')
