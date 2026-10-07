#!/usr/bin/env python3
"""Inspect dense synthetic annotations; restore the isolated AVD preferences."""
import argparse
import base64
import json
import re
from pathlib import Path
import subprocess
import struct
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', default='/tmp/thermal-annotation-check')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This preferences/font-scale check requires an emulator')
    package = 'com.thereprocase.thermalfield.emulator'
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    def adb(*command, data=None, check=True):
        return subprocess.run([args.adb, '-s', args.serial, *command], input=data,
                              check=check, capture_output=True, timeout=30)

    def preferences(data):
        # The command is constant; preference bytes go over stdin, not through
        # shell interpolation. This package cannot address the Pixel's data.
        adb('shell', f"run-as {package} sh -c 'cat > shared_prefs/display.xml'", data=data)

    recovery = output / 'restore-state.json'
    if recovery.exists():
        saved = json.loads(recovery.read_text())
        adb('shell','am','force-stop',package)
        preferences(base64.b64decode(saved['preferences']))
        for key,value in saved['settings'].items():
            adb('shell','settings','delete' if value == 'null' else 'put','system',key,*([] if value == 'null' else [value]))
        recovery.unlink()
    original = adb('shell', 'run-as', package, 'cat', 'shared_prefs/display.xml').stdout
    font_scale = adb('shell', 'settings', 'get', 'system', 'font_scale').stdout.decode().strip()
    rotation = adb('shell', 'cmd', 'window', 'user-rotation').stdout.decode().strip()
    original_rotation = adb('shell','settings','get','system','user_rotation').stdout.decode().strip()
    original_auto = adb('shell','settings','get','system','accelerometer_rotation').stdout.decode().strip()
    recovery.write_text(json.dumps({'preferences':base64.b64encode(original).decode(),
        'settings':{'font_scale':font_scale,'user_rotation':original_rotation,'accelerometer_rotation':original_auto}}))
    recovery.chmod(0o600)
    root = ET.fromstring(original)
    layout = {'version': 1, 'geometry': [], 'first': 0, 'second': 0, 'isotherm': 0, 'lower': 20, 'upper': 30, 'next_id': 17}
    for index in range(16):
        kind = index % 3 + 1
        layout['geometry'].append([index+1, kind, 128, 96, 128 if kind == 1 else 160, 96 if kind == 1 else 120])
    values = {'measurementLayout': json.dumps(layout), 'rotation': '0', 'flip': 'false', 'mirror': 'false', 'automatic': 'true', 'rotationLocked': 'false'}
    for key, value in values.items():
        for node in list(root):
            if node.get('name') == key:
                root.remove(node)
        if key == 'measurementLayout':
            ET.SubElement(root, 'string', name=key).text = value
        else:
            ET.SubElement(root, 'int' if key == 'rotation' else 'boolean', name=key, value=value)
    try:
        adb('shell', 'am', 'force-stop', package)
        preferences(ET.tostring(root, encoding='utf-8', xml_declaration=True))
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.5')
        for angle, name in [(0, 'portrait'), (1, 'landscape')]:
            adb('shell', 'am', 'force-stop', package)
            adb('shell','settings','put','system','accelerometer_rotation','0')
            adb('shell','settings','put','system','user_rotation',str(angle))
            adb('shell', 'am', 'start', '-n', f'{package}/com.thereprocase.thermalfield.MainActivity', '--ez', 'fixture', 'true')
            deadline = time.monotonic()+60
            while True:
                adb('shell','rm','-f','/data/local/tmp/thermal-annotation-ready.xml')
                adb('shell','uiautomator','dump','/data/local/tmp/thermal-annotation-ready.xml',check=False)
                result = adb('shell','cat','/data/local/tmp/thermal-annotation-ready.xml',check=False)
                if result.returncode == 0:
                    nodes = ET.fromstring(result.stdout)
                    if any('SYNTHETIC' in node.get('text','') for node in nodes.iter('node')):
                        break
                if time.monotonic() >= deadline:
                    raise AssertionError('Synthetic viewer did not become visible')
                time.sleep(.5)
            adb('shell','settings','put','system','accelerometer_rotation','0')
            adb('shell','settings','put','system','user_rotation',str(angle))
            adb('shell','cmd','window','user-rotation','lock',str(angle))
            deadline = time.monotonic()+30
            while True:
                screenshot = adb('exec-out', 'screencap', '-p').stdout
                width, height = struct.unpack('>II', screenshot[16:24])
                if (width < height) == (angle == 0):
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError(f'{name} orientation did not settle: {width}x{height}')
                time.sleep(.5)
            # Display rotation can precede the activity's Compose layout.
            deadline = time.monotonic()+30
            while True:
                adb('shell','uiautomator','dump','/data/local/tmp/thermal-annotation-layout.xml',check=False)
                result = adb('shell','cat','/data/local/tmp/thermal-annotation-layout.xml',check=False)
                if result.returncode == 0:
                    nodes = list(ET.fromstring(result.stdout).iter('node'))
                    controls = next((n for n in nodes if n.get('text') == 'Controls'),None)
                    capture = next((n for n in nodes if n.get('text') == 'Capture'),None)
                    if controls is not None and capture is not None:
                        control_top = list(map(int,re.findall(r'\d+',controls.get('bounds'))))[1]
                        capture_top = list(map(int,re.findall(r'\d+',capture.get('bounds'))))[1]
                        if (control_top == capture_top) == (angle == 1):
                            break
                if time.monotonic() >= deadline:
                    raise AssertionError(f'{name} activity layout did not settle')
                time.sleep(.5)
            time.sleep(1)
            (output / f'{name}.png').write_bytes(adb('exec-out','screencap','-p').stdout)
        print(json.dumps({'source': 'synthetic', 'geometry': 16, 'font_scale': 1.5, 'artifacts': str(output), 'visual_result': 'requires inspection'}))
    finally:
        adb('shell', 'am', 'force-stop', package)
        preferences(original)
        if font_scale == 'null':
            adb('shell', 'settings', 'delete', 'system', 'font_scale')
        else:
            adb('shell', 'settings', 'put', 'system', 'font_scale', font_scale)
        parts = rotation.split()
        if parts and parts[0] == 'lock':
            adb('shell', 'cmd', 'window', 'user-rotation', 'lock', parts[-1])
        else:
            adb('shell', 'cmd', 'window', 'user-rotation', 'free')
        for key,value in [('user_rotation',original_rotation),('accelerometer_rotation',original_auto)]:
            if value == 'null':
                adb('shell','settings','delete','system',key)
            else:
                adb('shell','settings','put','system',key,value)
        recovery.unlink()


if __name__ == '__main__':
    main()
