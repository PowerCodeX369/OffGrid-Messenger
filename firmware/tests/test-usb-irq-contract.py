#!/usr/bin/env python3
"""Offline source/host-syntax smoke tests; not real IDF build or hardware measurements."""
from pathlib import Path
import subprocess, hashlib
root=Path(__file__).resolve().parents[1]
base=root.parent/'OffGridMessenger_Phase1_4_10_2_InterruptUSB'
# The radio and browser photo ACK protocol are intentionally extended; the
# interrupt-driven USB implementation and rest of the stable baseline are not.
for rel in ('sdkconfig.defaults','main/Kconfig.projbuild','main/link_rate_policy.h','main/CMakeLists.txt',
            'web/styles.css','web/logo.svg','web/adaptive.js'):
    assert (root/rel).read_bytes()==(base/rel).read_bytes(),rel
old=(base/'main/main.c').read_text()
new=(root/'main/main.c').read_text()
for literal in ['usb_serial_jtag_driver_install(&cfg)','OFFGRID_USB_VFS_USE_DRIVER();',
                'usb_serial_jtag_read_bytes(dst,','KEYGET','KEYSET','esp_now_set_peer_rate_config(peer_mac,&cfg)',
                'pdMS_TO_TICKS(5) + 1','vTaskDelay(1)']:
    assert literal in old and literal in new,literal
print('PASS: original USB IRQ, NVS, PHY policy and watchdog preserved')
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
