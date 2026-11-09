#!/usr/bin/env python3
"""Compile actual batch-ACK parser and endian helpers from main.c in a host unit test.
This tests parsing/encoding but does NOT replace a full ESP-IDF firmware build.
"""
from pathlib import Path
import re, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'main/main.c').read_text()
def fragment(start,end):
    assert start in s and end in s
    return s[s.index(start):s.index(end)]
helpers=fragment('static uint32_t sack_read32(', '/* Keep this extension')
parse_mac=fragment('static bool parse_mac(', 'static int nibble(')
parser=fragment('static bool parse_batch_ack(', 'static void ble_commands(' if 'static void ble_commands(' in s else 'static void serial_task(')
pre="""#include <stdint.h>\n#include <stdio.h>\n#include <stdlib.h>\n#include <string.h>\n#include <stdbool.h>\n#include <ctype.h>\n#define SACK_MAX_IDS 6\n#define EV_APPACK_BATCH 8\ntypedef struct {int type;uint8_t mac[6];uint32_t session;uint16_t length;uint8_t text[180];} event_t;\n"""
main=r"""int main(void){
 uint8_t sample[4]={0};sack_write32(sample,0xfedcba98u);
 if(sack_read32(sample)!=0xfedcba98u)return 1;
 char c1[]="1C:DB:D4:3B:00:FC 1234 1 10 20 300 4294967295 42";
 event_t e={0};if(!parse_batch_ack(c1,&e)||e.length!=25||e.text[0]!=6||e.session!=1234)return 2;
 if(sack_read32(&e.text[21])!=42)return 3;
 char c2[]="1C:DB:D4:3B:00:FC 1234 1 2 3 4 5 6 7";
 if(parse_batch_ack(c2,&e))return 4;
 char c3[]="1C:DB:D4:3B:00:FC 1234 -1";
 if(parse_batch_ack(c3,&e))return 5;
 char c4[]="1C:DB:D4:3B:00:FC 1234 4294967296";
 if(parse_batch_ack(c4,&e))return 6;
 char c5[]="1C:DB:D4:3B:00:FC 1234";
 if(parse_batch_ack(c5,&e))return 7;
 char c6[]="INVALID-MAC 1234 1";
 if(parse_batch_ack(c6,&e))return 8;
 puts("PASS firmware SACK ID packing and bounded, nonsequential ACK command parsing");return 0;
}"""
with tempfile.TemporaryDirectory() as d:
    f=Path(d)/'sack-test.c';out=Path(d)/'test'
    f.write_text(pre+parse_mac+helpers+parser+main)
    subprocess.run(['gcc','-std=gnu11','-Wall','-Wextra','-Werror',str(f),'-o',str(out)],check=True)
    subprocess.run([str(out)],check=True)
