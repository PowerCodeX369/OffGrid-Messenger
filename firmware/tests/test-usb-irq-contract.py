#!/usr/bin/env python3
"""Offline source/host-syntax smoke tests; not real IDF build or hardware measurements."""
from pathlib import Path
import subprocess, hashlib
root=Path(__file__).resolve().parents[1]
base=Path('/mnt/data/OffGridMessenger_Phase1_4_10_1_USBRecovery')
if not base.exists():
    print('Base snapshot unavailable; compare against zipped release manually')
else:
    for f in sorted((base/'web').rglob('*')):
        if f.is_file():
            peer=root/f.relative_to(base)
            assert f.read_bytes()==peer.read_bytes(),f'Changed web UI: {f.name}'
    for f in ['main/link_rate_policy.h','CMakeLists.txt']:
        assert (root/f).read_bytes()==(base/f).read_bytes(),f'Changed stable source: {f}'
    old=(base/'main/main.c').read_text()
    new=(root/'main/main.c').read_text()
    start="        if(ch=='\\n') {"
    end='''        else {used=0;puts("ERROR|LINE_TOO_LONG");}'''
    assert old[old.index(start):old.index(end)+len(end)]==new[new.index(start):new.index(end)+len(end)],'Command parser changed'
    for literal in ['KEYGET','KEYSET','TYPE_BENCH_DATA','TYPE_BENCH_ACK','USBIN_BEGIN',
                    'USBIN_DONE','USBOUT_DONE','esp_now_set_peer_rate_config',
                    'pdMS_TO_TICKS(5) + 1','vTaskDelay(1)']:
        assert literal in new,literal
    print('PASS: web files, radio code, NVS commands, serial commands unchanged')
source=(root/'main/main.c').read_text()
assert 'usb_serial_jtag_driver_install(&cfg)' in source
assert 'OFFGRID_USB_VFS_USE_DRIVER();' in source
assert 'usb_serial_jtag_read_bytes(dst,' in source
assert 'USBMODE|IRQ' in source and 'USBMODE|LEGACY' in source
assert 'CONFIG_OFFGRID_USB_IRQ_RX=y' in (root/'sdkconfig.defaults').read_text()
assert (root/'main/Kconfig.projbuild').exists()
for irq in (0,1):
    command=['cc','-std=gnu11','-fsyntax-only','-w',
             '-Wno-error=implicit-function-declaration',
             '-Wno-error=int-conversion',f'-DCONFIG_OFFGRID_USB_IRQ_RX={irq}',
             '-I',str(root/'tests/host_stubs'),'-I',str(root/'main'),str(root/'main/main.c')]
    r=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    if r.returncode:raise RuntimeError(f'USB mode {irq}:\n{r.stderr}')
    print(f'PASS: host-stub C syntax, CONFIG_OFFGRID_USB_IRQ_RX={irq}')
print('NOTE: ESP-IDF linking and actual USB throughput cannot be verified in host stubs')
