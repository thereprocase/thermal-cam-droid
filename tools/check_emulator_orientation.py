#!/usr/bin/env python3
"""Check Android's sensor-driven full-screen rotation on an isolated AVD."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This sensor injection check requires an emulator')

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], check=True,
                              capture_output=True, text=True, timeout=30).stdout

    def tap(text):
        adb('shell', 'uiautomator', 'dump', '/data/local/tmp/thermal-orientation.xml')
        root = ET.fromstring(adb('shell', 'cat', '/data/local/tmp/thermal-orientation.xml'))
        node = next(n for n in root.iter('node') if text in (n.get('text'), n.get('content-desc')))
        x0, y0, x1, y1 = map(int, re.findall(r'\d+', node.get('bounds')))
        adb('shell', 'input', 'tap', str((x0+x1)//2), str((y0+y1)//2))

    def rotation():
        output = adb('shell', 'dumpsys', 'window', 'displays')
        return int(re.search(r'mRotation=(\d)', output).group(1))

    def sensor(values):
        adb('emu', 'sensor', 'set', 'acceleration', values)
        time.sleep(2)

    package = 'com.thereprocase.thermalfield.emulator'
    adb('shell', 'am', 'start', '-n', f'{package}/com.thereprocase.thermalfield.MainActivity', '--ez', 'fixture', 'true')
    time.sleep(2)
    tap('Full screen')
    time.sleep(1)
    seen = []
    locked = False
    try:
        orientation = adb('shell', 'dumpsys', 'activity', 'activities')
        actual = re.findall(r'mCurrentAppOrientation=.*', orientation)
        assert any('SCREEN_ORIENTATION_FULL_SENSOR' in value for value in actual), f'Full screen policy mismatch (check rotation lock): {actual}'
        for values in ['0:9.8:1', '9.8:0:1', '0:-9.8:1', '-9.8:0:1']:
            sensor(values)
            seen.append(rotation())
        assert set(seen) == {0, 1, 2, 3}, f'Sensor orientations missing: {seen}'
        tap('Lock')
        locked = True
        frozen = rotation()
        sensor('0:9.8:1')
        assert rotation() == frozen, 'Screen rotation lock did not hold'
        tap('Lock')
        locked = False
        sensor('0:9.8:1')
        assert rotation() == 0, 'Unlock did not restore sensor rotation'
        print(json.dumps({'source': 'AVD synthetic', 'sensor_rotations': seen, 'lock_unlock': 'passed', 'physical_mounting': 'not tested'}))
    finally:
        if locked:
            tap('Lock')
        sensor('0:9.8:1')
        tap('Exit full screen')


if __name__ == '__main__':
    main()
