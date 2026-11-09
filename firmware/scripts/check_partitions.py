#!/usr/bin/env python3
"""Local, SDK-independent layout check; real ESP-IDF partition generation is authoritative."""
from pathlib import Path
import csv
import sys
root = Path(__file__).resolve().parents[1]
file = root / 'partitions.csv'
rows=[]
for cols in csv.reader(file.read_text().splitlines()):
    cols=[col.strip() for col in cols]
    if not cols or not cols[0] or cols[0].startswith('#'): continue
    if len(cols)<5: raise SystemExit('Invalid partition entry')
    name,typ,sub,offset,size=cols[:5]
    rows.append((name,typ,sub,int(offset,0),int(size,0)))
assert len(rows)==3, rows
assert rows[0]==('nvs','data','nvs',0x9000,0x6000), 'NVS layout changed'
assert rows[1]==('phy_init','data','phy',0xF000,0x1000), 'PHY layout changed'
assert rows[2]==('factory','app','factory',0x10000,0x200000), 'Factory layout wrong'
for a,b in zip(rows,rows[1:]):
    assert a[3]+a[4]<=b[3], 'Overlapping partitions'
assert rows[-1][3]+rows[-1][4]<=0x400000, 'Requires >4 MiB flash'
print('PASS partition CSV: preserved NVS, PHY; 2 MiB factory at 0x10000.')
app=root/'build'/'offgrid_messenger_phase1.bin'
if app.exists():
    used=app.stat().st_size
    assert used<=rows[-1][4], f'Image still too large: {used} bytes'
    print(f'PASS binary fits: {used}/{rows[-1][4]} bytes; headroom {rows[-1][4]-used} bytes')
else:
    print('No local firmware binary found; run idf.py build to perform real size check.')
