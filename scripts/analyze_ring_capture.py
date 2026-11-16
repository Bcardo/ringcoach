"""Analyze private Ring notification JSONL without printing readings or identifiers."""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct

from ring_health_protocol import BulkAssembler, decode_sync_message
from ring_measurements import FEATURE_NAMES, decode_measurement


def analyze_capture(lines):
    assemblers = {}
    counts = Counter()
    features = Counter()
    errors = Counter()
    measurement_records = Counter()
    measurement_samples = Counter()
    measurement_errors = Counter()
    windows = []
    for line in lines:
        counts['lines'] += 1
        try:
            row = json.loads(line)
            if row.get('kind') != 'notification': continue
            data = bytes.fromhex(row['hex'])
            counts['notifications'] += 1
            if data[:2] != b'\x0a\x0a': continue
            counts['health_notifications'] += 1
            if len(data) < 3: raise ValueError('Truncated health header')
            if data[2] & 63 != 63:
                counts['health_control_notifications'] += 1
                continue
            assembler = assemblers.setdefault(row.get('connection', 0), BulkAssembler())
            message = assembler.feed(data)
            if message is None: continue
            result = decode_sync_message(message)
            counts['decoded_bulk_messages'] += 1
            features.update(result['features'])
            for record in result['health_records']:
                feature = record['feature']
                if feature not in FEATURE_NAMES: continue
                try:
                    decoded = decode_measurement(feature, record['body'])
                    name = FEATURE_NAMES[feature]
                    measurement_records[name] += 1
                    measurement_samples[name] += len(decoded.get('samples', decoded.get('windows', [])))
                except ValueError as error:
                    measurement_errors[str(error)] += 1
            counts['hrv_records'] += len(result['hrv_records'])
            for record in result['hrv_records']: windows.extend(record['windows'])
        except (ValueError, KeyError, TypeError) as error:
            errors[str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else 'Invalid capture row'] += 1
    unique = {(w['start_time'], w['end_time'], w['rmssd'], w['sdnn']) for w in windows}
    return {'counts': dict(counts), 'health_feature_counts': dict(sorted(features.items())),
            'decoded_hrv_windows': len(windows), 'unique_hrv_windows': len(unique),
            'decode_errors': dict(errors), 'incomplete_bulk_connections': sum(a.expected > 0 for a in assemblers.values()),
            'measurement_record_counts': dict(measurement_records),
            'measurement_sample_counts': dict(measurement_samples),
            'measurement_decode_errors': dict(measurement_errors),
            'hrv_reference_validation': 'not_compared'}, sorted(unique)


def compare_reference(windows, reference):
    def key(row):
        return int(row[0]), int(row[1]), struct.pack('<f', row[2]), struct.pack('<f', row[3])
    captured = {key(row) for row in windows}
    known = {key(row) for row in reference}
    return {'capture_windows_matching_reference': len(captured & known),
            'capture_windows_not_in_reference': len(captured - known),
            'reference_windows_not_in_capture': len(known - captured)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--reference', type=Path, help='Private JSON tuples: start_ms,end_ms,rmssd,sdnn')
    parser.add_argument('--private-hrv-output', type=Path, help='Optional local file for decoded HRV tuples; never printed')
    args = parser.parse_args()
    with args.capture.open(encoding='utf-8') as stream: report, windows = analyze_capture(stream)
    if args.reference:
        report['reference_comparison'] = compare_reference(windows, json.loads(args.reference.read_text(encoding='utf-8')))
        report['hrv_reference_validation'] = 'compared' if windows else 'no_captured_hrv_to_compare'
    if args.private_hrv_output:
        args.private_hrv_output.write_text(json.dumps(windows), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__': main()
