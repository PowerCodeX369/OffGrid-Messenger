#!/usr/bin/env python3
"""Compile the actual record emitter from main.c with host gcc and verify wire format."""
import pathlib, subprocess, tempfile
src = pathlib.Path(__file__).resolve().parents[1] / 'main' / 'main.c'
s = src.read_text()
start = s.index('static void emit_hex_record(')
end = s.index('static const char *link_mode_name(', start)
unit = s[start:end]
assert "for (size_t i = 0; i < length; ++i)" in unit
assert "(void)fwrite(line, 1, bytes, stdout);" in unit
assert 'setvbuf(stdout,NULL,_IOLBF,0);' in s
assert 'print_hex(' not in s
source = '#include <stdio.h>\n#include <string.h>\n#include <stdint.h>\n#define MAX_TEXT 180\n' + unit + '''\nint main(void) {
  setvbuf(stdout, NULL, _IOLBF, 0);
  unsigned char payload[180];
  for(int i=0;i<180;++i) payload[i]=(unsigned char)i;
  emit_hex_record("RX|AA:BB:CC:DD:EE:FF|123|42|", payload, 180);
  emit_hex_record("QUEUED|123|42|", payload, 0);
  emit_hex_record("QUEUED|123|43|", payload, 3);
  return 0;
}\n'''
with tempfile.TemporaryDirectory() as t:
    path = pathlib.Path(t) / 'test.c'
    exe = pathlib.Path(t) / 'test'
    path.write_text(source)
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',str(path),'-o',str(exe)], check=True)
    result = subprocess.run([str(exe)], check=True, stdout=subprocess.PIPE).stdout.decode('ascii')
lines=result.splitlines()
assert len(lines)==3
expected=''.join(f'{i:02X}' for i in range(180))
assert lines[0] == 'RX|AA:BB:CC:DD:EE:FF|123|42|'+expected
assert lines[1]=='QUEUED|123|42|'
assert lines[2]=='QUEUED|123|43|000102'
print('PASS: 180-byte HEX payload and RX/QUEUED records preserve exact wire format')
