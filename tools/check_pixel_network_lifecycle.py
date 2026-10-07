#!/usr/bin/env python3
"""Check foreground network recovery on an already connected debug app.

Reports scoped observations. A healthy desktop bridge and an unlocked handset
are prerequisites. The output excludes handset identifiers and source URLs.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--cycles', type=int, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.cycles <= 100:
        parser.error('cycles must be between 1 and 100')
    package = 'com.thereprocase.thermalfield'
    adb = [args.adb, '-s', args.serial]

    def command(*values):
        return subprocess.check_output(adb + list(values), text=True, stderr=subprocess.PIPE, timeout=20)

    def shell(*values):
        return command('shell', *values)

    pid = shell('pidof', package).strip()
    if not pid.isdigit():
        raise RuntimeError('Expected one running thermal app process')

    def descriptors():
        return len(shell('run-as', package, 'ls', '/proc/' + pid + '/fd').splitlines())

    result = {'baseline_fd_count': descriptors(), 'samples': [], 'completed': False}
    marker_id = uuid.uuid4().hex
    try:
        for cycle in range(1, args.cycles + 1):
            shell('input', 'keyevent', 'KEYCODE_HOME')
            time.sleep(.4)
            shell('am', 'start', '-n', package + '/.MainActivity')
            marker = marker_id + '-' + str(cycle)
            shell('log', '-t', 'ThermalLifecycleCheck', marker)
            # Require a log emitted after return. Cached healthy telemetry from
            # the prior session cannot establish foreground recovery.
            deadline = time.monotonic() + 12
            measurement = None
            while time.monotonic() < deadline:
                marked = False
                for line in command('logcat', '-d', '-t', '500', '-s', 'ThermalField', 'ThermalLifecycleCheck').splitlines():
                    if marker in line and 'ThermalLifecycleCheck:' in line:
                        marked = True
                    elif marked and ' I ThermalField: ' in line and re.search(r'\s' + pid + r'\s', line):
                        try:
                            candidate = json.loads(line.split(' I ThermalField: ', 1)[1])
                        except ValueError:
                            continue
                        if (candidate.get('source') == 'network' and candidate.get('received', 0) >= 25
                                and 0 <= candidate.get('frame_age_ms', -1) < 300 and not candidate.get('error')):
                            measurement = candidate
                if measurement is not None:
                    break
                time.sleep(.2)
            if measurement is None:
                raise RuntimeError('Fresh network telemetry was not observed after foreground return')
            if shell('pidof', package).strip() != pid:
                raise RuntimeError('Thermal process changed during lifecycle check')
            keys = ['received', 'rendered', 'malformed', 'overflow', 'source_sequence_gaps', 'fps',
                    'callback_to_swap_ms', 'presentation_latency_ms']
            sample = {key: measurement.get(key) for key in keys}
            sample.update(cycle=cycle, fd_count=descriptors())
            result['samples'].append(sample)
            print(json.dumps(sample), flush=True)
        result['completed'] = True
    except Exception as error:
        result['failure_type'] = type(error).__name__
        raise
    finally:
        try:
            result['final_fd_count'] = descriptors()
        except Exception as error:
            result['final_fd_count'] = None
            result['final_fd_error_type'] = type(error).__name__
        args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Subprocess exception strings can contain the private ADB endpoint.
        detail = ': ' + str(error) if isinstance(error, RuntimeError) else ''
        print('Lifecycle check failed: ' + type(error).__name__ + detail, file=sys.stderr)
        raise SystemExit(1)
