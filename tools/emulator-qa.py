"""Focused smoke-test helpers. Refuses to operate on physical phones."""
from pathlib import Path
import argparse
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.stdout.reconfigure(encoding='utf-8')
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / 'artifacts/device-qa'
OUT.mkdir(parents=True, exist_ok=True)
os.environ['ANDROID_USER_HOME'] = str(ROOT / '.tools/android-user')
parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['prepare', 'start', 'stop', 'status', 'database', 'capture', 'export', 'map'])
parser.add_argument('--serial', default='emulator-5556')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('Only the disposable test emulator may be used.')

def adb(*parts, binary=False, check=True):
    result = subprocess.run([str(ADB), '-s', args.serial, *parts], capture_output=True, check=check)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()

if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
    raise SystemExit('Not an Android emulator.')

def dump():
    for attempt in range(4):
        adb('shell', 'uiautomator', 'dump', '/sdcard/netcheck-qa.xml')
        raw = adb('exec-out', 'cat', '/sdcard/netcheck-qa.xml', binary=True)
        if raw.startswith(b'<?xml'):
            (OUT / 'last-ui.xml').write_bytes(raw)
            return ET.fromstring(raw)
        time.sleep(1)
    raise RuntimeError('UI hierarchy not ready')

def tap_text(label):
    root = dump()
    for node in root.iter('node'):
        if node.get('text') == label or node.get('content-desc') == label:
            x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            return
    raise RuntimeError(f'Visible control not found: {label}')

def screenshot(name):
    (OUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

if args.action == 'prepare':
    for permission in ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'READ_PHONE_STATE', 'POST_NOTIFICATIONS']:
        adb('shell', 'pm', 'grant', 'dev.netcheck.app', f'android.permission.{permission}')
    adb('shell', 'settings', 'put', 'secure', 'location_mode', '3')
    adb('shell', 'svc', 'wifi', 'disable')
    adb('emu', 'geo', 'fix', '126.9780', '37.5665')
    adb('shell', 'am', 'start', '-n', 'dev.netcheck.app/.MainActivity')
    print('Prepared emulator only; Seoul coordinate is synthetic test input.')
elif args.action == 'start':
    tap_text('현재'); time.sleep(1)
    tap_text('기록 시작'); time.sleep(6)
    screenshot('monitor-running')
    print('\n'.join(n.get('text','') for n in dump().iter('node') if n.get('text')))
elif args.action == 'stop':
    tap_text('현재'); time.sleep(1)
    adb('shell', 'input', 'swipe', '500', '700', '500', '1900', '400')
    time.sleep(1)
    tap_text('기록 중지'); time.sleep(2)
    screenshot('monitor-stopped')
    print('Stopped through UI.')
elif args.action == 'map':
    tap_text('지도'); time.sleep(5); screenshot('map-recorded')
    print('\n'.join(n.get('text','') for n in dump().iter('node') if n.get('text')))
elif args.action == 'export':
    tap_text('기록'); time.sleep(1); tap_text('ZIP 내보내기'); time.sleep(2)
    screenshot('export-chooser')
    print(adb('shell', 'run-as', 'dev.netcheck.app', 'ls', 'cache/exports'))
elif args.action == 'database':
    folder = OUT / 'db'; folder.mkdir(exist_ok=True)
    for suffix in ['', '-wal', '-shm']:
        result = subprocess.run([str(ADB),'-s',args.serial,'exec-out','run-as','dev.netcheck.app','cat',f'databases/netcheck.db{suffix}'],capture_output=True)
        if result.returncode == 0: (folder / f'netcheck.db{suffix}').write_bytes(result.stdout)
    with sqlite3.connect(folder / 'netcheck.db') as connection:
        connection.row_factory = sqlite3.Row
        summary = {table: [dict(row) for row in connection.execute(f'SELECT * FROM {table} ORDER BY id')] for table in ['sessions','measurements','speed_tests','events']}
    (OUT / 'database-observations.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:len(v) for k,v in summary.items()},ensure_ascii=False))
    print(json.dumps([{'id':x['id'],'status':x['status'],'network':x['networkType'],'operator':x['operator'],'pings':json.loads(x['probesJson']),'locationAgeMs':x['locationAgeMs']} for x in summary['measurements']],ensure_ascii=False))
else:
    screenshot('current-state')
    print('\n'.join(n.get('text','') for n in dump().iter('node') if n.get('text')))
