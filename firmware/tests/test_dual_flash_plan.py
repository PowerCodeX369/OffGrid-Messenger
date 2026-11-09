"""No-hardware check for two-port flashing orchestration and argument modes."""
import importlib.util
from pathlib import Path
from unittest.mock import patch
import subprocess, sys
root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('dual', root/'scripts/flash_both.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
with patch('importlib.metadata.version', return_value='5.0.0'):
    with __import__('tempfile').TemporaryDirectory() as temp:
        build=Path(temp)
        (build/'flash_args').write_text('0x0 bootloader.bin')
        a=mod.esptool_command(build,'/dev/ttyACM0',460800)
        b=mod.esptool_command(build,'/dev/ttyACM1',460800)
        assert '--port' in a and a[a.index('--port')+1]=='/dev/ttyACM0'
        assert b[b.index('--port')+1]=='/dev/ttyACM1'
        assert 'write-flash' in a and '@flash_args' in a
        assert a[a.index('--chip')+1]=='esp32c3'
        print('PASS: generated-flash-args esptool v5 dual-device commands')
with patch('importlib.metadata.version', return_value='4.9.0'):
    with __import__('tempfile').TemporaryDirectory() as temp:
        build=Path(temp)
        (build/'flash_project_args').write_text('0x0 bootloader.bin')
        a=mod.esptool_command(build,'/dev/ttyACM0',460800)
        assert 'write_flash' in a and '@flash_project_args' in a
        print('PASS: esptool v4 syntax fallback')
r=subprocess.run([sys.executable,str(root/'scripts/flash_both.py'),'/dev/null','/dev/null','--skip-build'],capture_output=True,text=True)
assert r.returncode!=0 and 'DIFFERENT' in r.stderr
print('PASS: rejects duplicate physical ports')
