"""Bluetooth rate command whitelist guard and dispatcher shape test."""
from pathlib import Path
base=Path(__file__).resolve().parents[1]
bridge=(base/'main/ble_bridge.c').read_text()
main=(base/'main/main.c').read_text()
for mode in ['AUTO','SPEED','RANGE','LR500','LR250']:
    assert f'!strcmp(s,"RATE {mode}")' in bridge
    assert f'"{mode}"' in main
assert '!strcmp(line,"RATE ")' not in main
assert 'if(!auth){push("AUTH_REQUIRED",false);return;}' in bridge
assert bridge.index('if(!auth){push("AUTH_REQUIRED",false);return;}') < bridge.index('if(!allow(line))')
assert 'e.type=EV_RATE;' in main and 'adjust_phy();' in main
assert 'LINK_REPORT_MS 2500' in main
print('PASS: only five authenticated BLE rate commands and periodic LINK reporting')
