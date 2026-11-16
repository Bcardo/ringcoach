"""Passive Galaxy Ring health decoding. Never sends acknowledgments or changes a device.

Wire layout independently checked against the locally installed companion parser.
An HRV body alone does not prove a Bluetooth transfer, units, or fresh-night delivery.
"""
import math
import struct

EPOCH_1990_MS = 631152000000


class BulkAssembler:
    """One instance per connection AND direction; validates the six-byte bulk header."""
    def __init__(self, limit=1024 * 1024):
        self.limit = limit
        self.reset()

    def reset(self):
        self.parts = []
        self.expected = 0
        self.window = None
        self.size = 0

    def feed(self, frame):
        if len(frame) < 8 or frame[:2] != b'\x0a\x0a' or frame[2] & 63 != 63:
            raise ValueError('Not a complete health bulk-fragment header')
        flag, window = frame[3] & 3, (frame[3] >> 2) & 1
        number = struct.unpack_from('<I', frame, 4)[0]
        body = frame[8:]
        if flag not in (0, 1, 2):
            self.reset(); raise ValueError('Unknown bulk-fragment flag')
        if flag == 0:
            if number != 0:
                self.reset(); raise ValueError('First chunk must have index zero')
            if self.expected == 1 and self.window == window and self.parts == [body]:
                return None  # Exact retransmission of the first chunk.
            self.reset(); self.window = window
        elif self.window is None and flag == 2 and number == 0:
            self.window = window  # Single final chunk.
        if self.window != window or number != self.expected:
            self.reset(); raise ValueError('Missing, reordered, or cross-window bulk chunk')
        if self.size + len(body) > self.limit:
            self.reset(); raise ValueError('Bulk message exceeds bound')
        self.parts.append(body); self.size += len(body); self.expected += 1
        if flag == 2:
            result = b''.join(self.parts); self.reset(); return result
        return None


def ring_time_ms(seconds):
    return seconds * 1000 + EPOCH_1990_MS if seconds else None


def decode_hrv_body(data):
    """Decode an already delimited feature-150 record, including all binning windows."""
    position = 0
    fields = {}
    windows = []
    seen = set()
    while position < len(data):
        tag = data[position]; position += 1
        if tag in seen:
            raise ValueError('Duplicate HRV field')
        seen.add(tag)
        size = 16 if tag == 117 else 4
        if tag not in (1, 2, 3, 5, 16, 117) or position + size > len(data):
            raise ValueError('Unknown or truncated HRV field')
        value = data[position:position + size]; position += size
        if tag == 117:
            continue  # Do not expose a personal record identifier in summaries.
        number = struct.unpack('<I', value)[0]
        if tag in (1, 2, 5):
            fields[{1: 'start_time', 2: 'end_time', 5: 'update_time'}[tag]] = ring_time_ms(number)
        elif tag == 3:
            fields['time_offset_raw'] = number
        else:
            if number % 16 or position + number > len(data):
                raise ValueError('Truncated or misaligned HRV binning data')
            end = position + number
            while position < end:
                sdnn, rmssd, start, stop = struct.unpack_from('<ffII', data, position)
                position += 16
                if not all(math.isfinite(x) and x >= 0 for x in (sdnn, rmssd)) or not 0 < start <= stop:
                    raise ValueError('Invalid HRV window')
                windows.append({'start_time': ring_time_ms(start), 'end_time': ring_time_ms(stop),
                                'rmssd': rmssd, 'sdnn': sdnn})
    return {'fields': fields, 'windows': windows}


def decode_sync_message(message):
    """Strict supported subset: sequence, device info, and HRV; no byte-pattern guessing."""
    if len(message) < 3 or message[0] not in (1, 2) or message[1] & 63 != 2:
        raise ValueError('Not a supported client health-sync message')
    position = 3
    features = []
    records = []
    health_records = []
    for _ in range(message[2]):
        if position >= len(message):
            raise ValueError('Truncated sync parameter list')
        tag = message[position]; position += 1
        if tag == 224:
            position += 2
        elif tag == 5:
            if position >= len(message): raise ValueError('Truncated device-info length')
            size = message[position]; position += 1 + size
            features.append(tag)
        elif tag == 16:
            features.append(tag)
            while True:
                if position >= len(message): raise ValueError('Truncated sleep record')
                more = message[position]; position += 1
                if more not in (0, 1): raise ValueError('Invalid next-record flag')
                start = position
                seen = set()
                for _ in range(10):
                    if position >= len(message): raise ValueError('Truncated sleep field')
                    field = message[position]; position += 1
                    if field in seen: raise ValueError('Duplicate sleep field')
                    seen.add(field)
                    if field not in (1, 2, 3, 5, 80, 81, 87, 88, 89, 117):
                        raise ValueError('Unknown sleep field')
                    position += 16 if field == 117 else 1 if field == 81 else 4
                    if position > len(message): raise ValueError('Truncated sleep field')
                health_records.append({'feature': tag, 'body': message[start:position]})
                if not more: break
        elif tag in (10, 12, 19, 20, 136, 148, 149, 150, 225, 226, 227):
            features.append(tag)
            while True:
                if position + 5 > len(message): raise ValueError('Truncated HRV record header')
                more = message[position]
                if more not in (0, 1): raise ValueError('Invalid next-record flag')
                size = struct.unpack_from('<I', message, position + 1)[0]; position += 5
                if position + size > len(message): raise ValueError('Truncated HRV record body')
                if tag == 150:
                    records.append(decode_hrv_body(message[position:position + size]))
                health_records.append({'feature': tag, 'body': message[position:position + size]})
                position += size
                if not more: break
        else:
            raise ValueError(f'Unsupported sync feature {tag}; cannot classify this payload safely')
        if position > len(message): raise ValueError('Truncated sync field')
    if position != len(message): raise ValueError('Unconsumed sync bytes')
    return {'client': message[0], 'features': features, 'hrv_records': records,
            'health_records': health_records}
