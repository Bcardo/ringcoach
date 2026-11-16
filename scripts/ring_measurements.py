"""Strict passive decoders for captured Ring records; returns private measurements.

These are protocol measurements, not Samsung's daily summaries or clinical results.
Unknown fields fail closed. No device commands, keys or network access are used.
"""
import math
import struct

from ring_health_protocol import decode_hrv_body, ring_time_ms

FEATURE_NAMES = {10: 'heart_rate', 16: 'sleep', 19: 'stress', 148: 'movement',
                 149: 'respiration', 150: 'hrv', 225: 'skin_temperature',
                 226: 'blood_oxygen', 227: 'sleep_raw'}


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, size):
        if size < 0 or self.pos + size > len(self.data):
            raise ValueError('Truncated measurement field')
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def unpack(self, fmt):
        return struct.unpack('<' + fmt, self.take(struct.calcsize('<' + fmt)))

    def integer(self, size=4):
        return int.from_bytes(self.take(size), 'little')

    def block(self):
        return self.take(self.integer())

    def remaining(self):
        return self.pos < len(self.data)


def fixed_rows(data, fmt):
    size = struct.calcsize('<' + fmt)
    if len(data) % size: raise ValueError('Misaligned measurement array')
    return struct.iter_unpack('<' + fmt, data)


def decode_measurement(feature, body):
    if feature == 150:
        return decode_hrv_body(body)
    if feature not in FEATURE_NAMES: raise ValueError('Unsupported measurement feature')
    reader = Reader(body)
    fields, samples, seen = {}, [], set()
    result = {'fields': fields, 'samples': samples}
    while reader.remaining():
        tag = reader.integer(1)
        if tag in seen: raise ValueError('Duplicate measurement field')
        seen.add(tag)
        if tag in (117, 83) and (tag == 117 or feature == 227):
            reader.take(16)  # Deliberately omit personal identifiers.
        elif tag in (1, 2, 5):
            fields[{1: 'start_time', 2: 'end_time', 5: 'update_time'}[tag]] = ring_time_ms(reader.integer())
        elif tag == 3:
            fields['time_offset_raw'] = reader.unpack('i')[0]
        elif feature in (148, 149) and tag == 16:
            name = 'activity_level' if feature == 148 else 'respiratory_rate'
            for value, start, end in fixed_rows(reader.block(), 'fII'):
                if not math.isfinite(value): raise ValueError('Nonfinite measurement')
                samples.append({'start_time': ring_time_ms(start), 'end_time': ring_time_ms(end), name: value})
        elif feature == 10 and tag in (32, 33, 34, 35, 37):
            fields[{32: 'heart_rate', 33: 'min', 34: 'max', 35: 'heart_beat_count', 37: 'tag'}[tag]] = reader.integer(2 if tag == 37 else 1)
        elif feature == 10 and tag == 36:
            for offset, value, minimum, maximum in fixed_rows(reader.block(), 'BBBB'):
                if fields.get('start_time') is None: raise ValueError('Missing heart rate start time')
                start = fields['start_time'] + offset * 60000
                samples.append({'start_time': start, 'end_time': start + 59000,
                                'heart_rate': value, 'min': minimum, 'max': maximum})
        elif feature == 19 and tag in (96, 97, 102, 103):
            fields[{96: 'score', 97: 'tag', 102: 'min', 103: 'max'}[tag]] = reader.integer(4 if tag == 97 else 1)
        elif feature == 19 and tag == 98:
            for offset, score, maximum, minimum, flag, level in fixed_rows(reader.block(), 'BBBBBB'):
                if fields.get('start_time') is None: raise ValueError('Missing stress start time')
                start = fields['start_time'] + offset * 60000
                samples.append({'start_time': start, 'end_time': start + 59000,
                                'score': score, 'max': maximum, 'min': minimum, 'flag': flag, 'level': level})
        elif feature == 16 and tag in (80, 81, 87, 88, 89):
            name = {80: 'efficiency', 81: 'has_sleep_data', 87: 'latency_seconds',
                    88: 'bedtime_detection_delay_seconds', 89: 'wakeup_detection_delay_seconds'}[tag]
            fields[name] = reader.unpack('f')[0] if tag == 80 else reader.integer(1 if tag == 81 else 4)
        elif feature == 225 and tag == 16:
            fields['tracked_by'] = reader.integer(1)
        elif feature == 225 and tag == 165:
            for start, end, value, ambient in fixed_rows(reader.block(), 'IIff'):
                if not all(math.isfinite(x) for x in (value, ambient)): raise ValueError('Nonfinite temperature')
                samples.append({'start_time': ring_time_ms(start), 'end_time': ring_time_ms(end),
                                'object_temperature': value, 'ambient_temperature': ambient})
        elif feature == 225 and tag == 166:
            result['hr_rri'] = [{'start_time': ring_time_ms(start), 'end_time': ring_time_ms(end),
                                 'heart_rate': hr, 'rri_raw': rri}
                                for start, end, hr, rri in fixed_rows(reader.block(), 'IIBH')]
        elif feature == 226 and tag in (37, 169, 174):
            fields[{37: 'tag', 169: 'field_169', 174: 'field_174'}[tag]] = reader.integer(2 if tag == 37 else 1)
        elif feature == 226 and tag in (173, 175):
            # Preserve only lengths here: these are encoded optical channels,
            # not directly interpretable saturation percentages.
            fields[f'encoded_optical_field_{tag}_bytes'] = len(reader.block())
        elif feature == 227 and tag == 173:
            width = reader.integer(1)
            if width == 0: raise ValueError('Empty sleep raw row width')
            block = Reader(reader.block())
            while block.remaining():
                acc = block.unpack('I' * width)
                count = block.integer(1)
                rri = [block.unpack('BH') for _ in range(count)]
                samples.append({'acc_raw': acc, 'rri_raw': rri})
            fields['acc_width'] = width
        else:
            raise ValueError(f'Unsupported measurement field {tag} for feature {feature}')
    return result
