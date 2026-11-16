import json
import struct
import unittest

from analyze_ring_capture import analyze_capture
from ring_health_protocol import decode_sync_message, EPOCH_1990_MS
from ring_measurements import decode_measurement


def field(tag, value):
    return bytes([tag]) + struct.pack('<I', value)


def record(tag, body):
    return bytes([tag, 0]) + struct.pack('<I', len(body)) + body


def sleep_body():
    return (field(1, 100000) + field(2, 126400) + field(3, 0) + field(5, 126500)
            + b'\x75' + bytes(16) + b'\x50' + struct.pack('<f', 90)
            + b'\x51\x01' + field(87, 60) + field(88, 120) + field(89, 30))


class RingMeasurementsTest(unittest.TestCase):
    def test_mixed_oxygen_sleep_and_hrv_reaches_later_hrv(self):
        hrv = field(16, 16) + struct.pack('<ffII', 70.5, 61.25, 100000, 100300)
        message = (b'\x01\x82\x03' + record(226, field(1, 100000))
                   + b'\x10\x00' + sleep_body() + record(150, hrv))
        decoded = decode_sync_message(message)
        self.assertEqual(decoded['features'], [226, 16, 150])
        self.assertEqual(len(decoded['hrv_records'][0]['windows']), 1)
        self.assertEqual(decode_measurement(16, decoded['health_records'][1]['body'])['fields']['latency_seconds'], 60)
        for broken in (message[:-1], b'\x01\x82\x01\x10\x00' + sleep_body()[:-1]):
            with self.assertRaises(ValueError): decode_sync_message(broken)

    def test_heart_rate_offsets_and_array_bounds(self):
        body = field(1, 100000) + field(36, 4) + bytes([2, 66, 60, 70])
        sample = decode_measurement(10, body)['samples'][0]
        self.assertEqual(sample['start_time'], EPOCH_1990_MS + 100000000 + 120000)
        self.assertEqual(sample['heart_rate'], 66)
        for bad in (body[:-1], field(36, 3) + bytes(3), field(36, 4) + bytes(4)):
            with self.assertRaises(ValueError): decode_measurement(10, bad)

    def test_temperature_and_hr_rri_are_distinct_arrays(self):
        body = field(165, 16) + struct.pack('<IIff', 100000, 100060, 32.5, 24.25)
        body += field(166, 11) + struct.pack('<IIBH', 100000, 100060, 66, 900)
        result = decode_measurement(225, body)
        self.assertEqual(result['samples'][0]['object_temperature'], 32.5)
        self.assertEqual(result['hr_rri'][0]['rri_raw'], 900)
        with self.assertRaises(ValueError): decode_measurement(225, body[:-1])

    def test_sleep_raw_preserves_candidate_rri_without_assuming_units(self):
        raw = struct.pack('<IIBBH', 42, 43, 1, 7, 900)
        body = b'\xad\x02' + struct.pack('<I', len(raw)) + raw
        result = decode_measurement(227, body)
        self.assertEqual(result['samples'][0], {'acc_raw': (42, 43), 'rri_raw': [(7, 900)]})
        with self.assertRaises(ValueError): decode_measurement(227, body[:-1])

    def test_optical_channels_are_not_reported_as_spo2_percentages(self):
        result = decode_measurement(226, field(173, 4) + b'\x96\x00\x00\x00')
        self.assertEqual(result['samples'], [])
        self.assertEqual(result['fields'], {'encoded_optical_field_173_bytes': 4})
        with self.assertRaises(ValueError): decode_measurement(226, b'\xff')

    def test_analysis_summary_omits_measurement_values_and_identifiers(self):
        message = b'\x01\x82\x01' + record(225, field(165, 16) + struct.pack('<IIff', 100000, 100060, 32.5, 24.25))
        frame = b'\x0a\x0a\x3f\x0a' + bytes(4) + message
        report, _ = analyze_capture([json.dumps({'kind': 'notification', 'hex': frame.hex()})])
        self.assertEqual(report['measurement_sample_counts'], {'skin_temperature': 1})
        self.assertNotIn('32.5', json.dumps(report))
        self.assertNotIn('100000', json.dumps(report))


if __name__ == '__main__': unittest.main()
