"""Copy the owner's research capture to ignored private storage; print counts only."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from analyze_ring_capture import analyze_capture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb.exe') if os.environ.get('ANDROID_HOME') else shutil.which('adb')
    parser.add_argument('--adb', default=default_adb, required=not bool(default_adb))
    parser.add_argument('--out', type=Path, default=Path('private/bluetooth-overnight'))
    args = parser.parse_args()
    def read(name):
        return subprocess.check_output([args.adb, 'exec-out', 'run-as', 'com.bcardo.ringcoach.bleresearch',
                                        'cat', 'files/overnight/' + name], timeout=20)
    status_raw = read('status.json')
    status = json.loads(status_raw)
    name = status['capture_file']
    if not re.fullmatch(r'capture-[0-9]+\.jsonl', name): raise ValueError('Invalid capture filename')
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'status.json').write_bytes(status_raw)
    data = read(name)
    (args.out / name).write_bytes(data)
    report, _ = analyze_capture(data.decode('utf-8').splitlines())
    report['recorder'] = {key: status.get(key) for key in ('state', 'started_at', 'expires_at', 'connected',
        'packet_count', 'health_packet_count', 'subscription_status', 'reason')}
    (args.out / 'summary.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__': main()
