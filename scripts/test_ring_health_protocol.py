import struct
import unittest
import json
from analyze_ring_capture import analyze_capture, compare_reference
from ring_health_protocol import BulkAssembler, EPOCH_1990_MS, decode_hrv_body, decode_sync_message


def chunk(index, flag, body, window=0):
    return b'\x0a\x0a\x3f' + bytes([8 | window << 2 | flag]) + struct.pack('<I', index) + body


class RingHealthProtocolTest(unittest.TestCase):
    def body(self):
        window = struct.pack('<ffII', 70.5, 61.25, 100000, 100300)
        return b'\x01' + struct.pack('<I', 100000) + b'\x02' + struct.pack('<I', 100300) + b'\x10' + struct.pack('<I', len(window)) + window

    def test_hrv_uses_integer_1990_epoch_and_sdnn_then_rmssd(self):
        result = decode_hrv_body(self.body())
        self.assertEqual(result['windows'], [{'start_time': EPOCH_1990_MS + 100000000,
            'end_time': EPOCH_1990_MS + 100300000, 'rmssd': 61.25, 'sdnn': 70.5}])

    def test_reassembles_hrv_across_three_chunks_before_decoding(self):
        body = self.body()
        message = b'\x01\x82\x02\xe0\x01\x00\x96\x00' + struct.pack('<I', len(body)) + body
        assembler = BulkAssembler()
        self.assertIsNone(assembler.feed(chunk(0, 0, message[:9])))
        self.assertIsNone(assembler.feed(chunk(1, 1, message[9:20])))
        result = decode_sync_message(assembler.feed(chunk(2, 2, message[20:])))
        self.assertEqual(result['features'], [150])
        self.assertEqual(len(result['hrv_records'][0]['windows']), 1)

    def test_rejects_missing_chunks_and_cross_window_data(self):
        for bad in [chunk(2, 2, b'c'), chunk(1, 2, b'c', 1)]:
            assembler = BulkAssembler(); assembler.feed(chunk(0, 0, b'a'))
            with self.assertRaises(ValueError): assembler.feed(bad)
        with self.assertRaises(ValueError): BulkAssembler(limit=2).feed(chunk(0, 2, b'abc'))

    def test_does_not_identify_hrv_from_a_byte_inside_device_info(self):
        result = decode_sync_message(b'\x01\x82\x01\x05\x02\x96\x00')
        self.assertEqual(result['features'], [5])
        self.assertEqual(result['hrv_records'], [])

    def test_rejects_partial_invalid_and_unknown_hrv_data(self):
        for body in [self.body()[:-1], b'\x10\x01\x00\x00\x00\x00', b'\xff',
                     b'\x10' + struct.pack('<IffII', 16, float('nan'), 1, 1, 2)]:
            with self.assertRaises(ValueError): decode_hrv_body(body)
        with self.assertRaises(ValueError): decode_sync_message(b'\x01\x82\x01\x99')

    def test_analysis_keeps_values_private_and_compares_float32_reference(self):
        body = self.body()
        message = b'\x01\x82\x01\x96\x00' + struct.pack('<I', len(body)) + body
        line = json.dumps({'kind': 'notification', 'connection': 1, 'hex': chunk(0, 2, message).hex()})
        report, windows = analyze_capture([line])
        self.assertEqual(report['unique_hrv_windows'], 1)
        self.assertNotIn('61.25', json.dumps(report))
        self.assertEqual(compare_reference(windows, windows)['capture_windows_matching_reference'], 1)
        self.assertEqual(compare_reference(windows, [])['capture_windows_not_in_reference'], 1)

    def test_analysis_never_joins_different_connections(self):
        rows = [json.dumps({'kind': 'notification', 'connection': connection, 'hex': frame.hex()})
            for connection, frame in [(1, chunk(0, 0, b'\x01\x82')), (2, chunk(1, 2, b'\x00'))]]
        report, windows = analyze_capture(rows)
        self.assertEqual(windows, [])
        self.assertTrue(report['decode_errors'])


if __name__ == '__main__': unittest.main()
