"""Summarize Galaxy Ring ATT traffic from an Android Bluetooth snoop log."""

import argparse
from collections import Counter
from contextlib import contextmanager
from dataclasses import dataclass, field
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
from zipfile import BadZipFile, ZipFile
from ring_health_protocol import BulkAssembler, decode_sync_message


BTSNOOP_H4_HEADER = b"btsnoop\x00" + struct.pack(">II", 1, 1002)
RECORD = struct.Struct(">IIIIQ")
ATT_VALUE_OPCODES = {0x12: "write", 0x52: "write_command", 0x1B: "notification", 0x1D: "indication"}
SNOOP_FILENAME = re.compile(r"btsnoop_hci(?:_[a-zA-Z0-9_-]+)?\.log(?:\.last)?")


@contextmanager
def snoop_file(path, member):
    if path.suffix.lower() != ".zip":
        if member is not None:
            raise ValueError("--member requires a bug-report ZIP.")
        with path.open("rb") as stream:
            yield stream
        return

    with ZipFile(path) as archive:
        candidates = [name for name in archive.namelist()
                      if SNOOP_FILENAME.fullmatch(PurePosixPath(name).name)]
        if member is None:
            current = [name for name in candidates if name.endswith(".log")]
            if len(current) == 1:
                member = current[0]
            elif len(candidates) == 1:
                member = candidates[0]
            elif not candidates:
                raise ValueError("No full Bluetooth HCI snoop log was found in the ZIP.")
            else:
                raise ValueError("Multiple full snoop logs found. Pass the archive path to one with --member.")
        if member not in candidates:
            raise ValueError("--member must name a full Bluetooth HCI snoop log in the ZIP.")
        with archive.open(member) as stream:
            yield stream


def records(stream):
    if stream.read(len(BTSNOOP_H4_HEADER)) != BTSNOOP_H4_HEADER:
        raise ValueError("Expected a version 1 BTSnoop log with HCI H4 packets.")
    while header := stream.read(RECORD.size):
        if len(header) != RECORD.size:
            raise ValueError("Truncated BTSnoop record header.")
        original, included, flags, _, timestamp = RECORD.unpack(header)
        if included > original or included > 1024 * 1024:
            raise ValueError("Invalid BTSnoop record length.")
        packet = stream.read(included)
        if len(packet) != included:
            raise ValueError("Truncated BTSnoop packet.")
        yield packet, flags & 1, timestamp


@dataclass
class Connection:
    ordinal: int
    saw_ring_hello: bool = False
    att_messages: int = 0
    health_channel: Counter = field(default_factory=Counter)
    health_reply_lengths: Counter = field(default_factory=Counter)
    first_health_time: int | None = None
    last_health_time: int | None = None
    characteristic_lookup_pending: bool = False
    heart_rate_handles: set = field(default_factory=set)
    heart_rate_notifications: int = 0
    rr_notifications: int = 0
    decoded_bulk_messages: int = 0
    health_features: Counter = field(default_factory=Counter)
    hrv_records: int = 0
    hrv_windows: int = 0
    bulk_errors: Counter = field(default_factory=Counter)
    session_key_exchange_messages: int = 0
    bulk: BulkAssembler = field(default_factory=BulkAssembler)

    def observe_att(self, att, received, timestamp):
        if not att:
            return
        self.att_messages += 1
        opcode = att[0]
        if opcode == 0x08 and not received:
            self.characteristic_lookup_pending = len(att) == 7 and att[5:7] == b"\x03\x28"
        elif received and opcode in (0x01, 0x09):
            if self.characteristic_lookup_pending and opcode == 0x09 and len(att) >= 9 and att[1] == 7:
                for entry in range(2, len(att) - 6, 7):
                    if att[entry + 5:entry + 7] == b"\x37\x2a":
                        self.heart_rate_handles.add(int.from_bytes(att[entry + 3:entry + 5], "little"))
            self.characteristic_lookup_pending = False

        if opcode not in ATT_VALUE_OPCODES or len(att) < 4:
            return
        attribute = int.from_bytes(att[1:3], "little")
        payload = att[3:]
        if received and opcode in (0x1B, 0x1D):
            if payload.startswith(b"\xa7") and b"galaxy ring" in payload.lower():
                self.saw_ring_hello = True
            if attribute in self.heart_rate_handles:
                self.heart_rate_notifications += 1
                rr_offset = 1 + (2 if payload[0] & 0x01 else 1) + (2 if payload[0] & 0x08 else 0)
                rr_bytes = len(payload) - rr_offset
                self.rr_notifications += bool(payload[0] & 0x10 and rr_bytes >= 2 and rr_bytes % 2 == 0)

        if payload.startswith(b"\x0a\x0a"):
            direction = "ring_to_phone" if received else "phone_to_ring"
            self.health_channel[f"{direction}_{ATT_VALUE_OPCODES[opcode]}"] += 1
            if self.first_health_time is None:
                self.first_health_time = timestamp
            self.last_health_time = timestamp
            if received and payload.startswith(b"\x0a\x0a\x3f"):
                self.health_reply_lengths[len(payload)] += 1
                try:
                    message = self.bulk.feed(payload)
                    if message is not None:
                        decoded = decode_sync_message(message)
                        self.decoded_bulk_messages += 1
                        self.health_features.update(decoded['features'])
                        self.hrv_records += len(decoded['hrv_records'])
                        self.hrv_windows += sum(len(record['windows']) for record in decoded['hrv_records'])
                except ValueError as error:
                    self.bulk_errors[str(error)] += 1
            if received and len(payload) == 93 and payload[2:4] == b'\x04\x87' and payload[4] == 224 and payload[7:9] == b'\x54\x00':
                self.session_key_exchange_messages += 1

    def report(self):
        duration = None if self.first_health_time is None else round(
            (self.last_health_time - self.first_health_time) / 1_000_000, 3,
        )
        return {
            "connection": self.ordinal,
            "ring_hello_seen": self.saw_ring_hello,
            "channel_10_messages": dict(self.health_channel),
            "channel_10_duration_seconds": duration,
            "channel_10_3f_reply_lengths": dict(sorted(self.health_reply_lengths.items())),
            "standard_heart_rate_characteristic_discovered": bool(self.heart_rate_handles),
            "standard_heart_rate_notifications": self.heart_rate_notifications,
            "standard_rr_interval_notifications": self.rr_notifications,
            "decoded_health_bulk_messages": self.decoded_bulk_messages,
            "health_feature_counts": dict(sorted(self.health_features.items())),
            "decoded_hrv_records": self.hrv_records,
            "decoded_hrv_windows": self.hrv_windows,
            "bulk_decode_errors": dict(self.bulk_errors),
            "session_key_exchange_messages": self.session_key_exchange_messages,
        }


def inspect(stream, selected_connection=None):
    live = {}
    connections = []
    fragments = {}
    truncated = 0

    def begin(handle):
        previous = live.pop(handle, None)
        if previous is not None:
            for direction in (0, 1):
                fragments.pop((previous.ordinal, direction), None)
        connection = Connection(len(connections) + 1)
        connections.append(connection)
        live[handle] = connection
        return connection

    for packet, received, timestamp in records(stream):
        if packet[:1] == b"\x04":
            if len(packet) >= 7 and packet[1] == 0x3E and packet[3] in (0x01, 0x0A, 0x29) and packet[4] == 0:
                begin(int.from_bytes(packet[5:7], "little") & 0x0FFF)
            elif len(packet) >= 7 and packet[1] == 0x05 and packet[3] == 0:
                handle = int.from_bytes(packet[4:6], "little") & 0x0FFF
                connection = live.pop(handle, None)
                if connection is not None:
                    for direction in (0, 1):
                        fragments.pop((connection.ordinal, direction), None)
            continue
        if packet[:1] != b"\x02" or len(packet) < 5:
            continue
        handle_flags, data_length = struct.unpack_from("<HH", packet, 1)
        handle, boundary = handle_flags & 0x0FFF, (handle_flags >> 12) & 3
        connection = live.get(handle) or begin(handle)
        key = connection.ordinal, received
        acl = packet[5:]
        if len(acl) != data_length:
            fragments.pop(key, None)
            truncated += 1
            continue

        if boundary == 1:
            if key not in fragments:
                continue
            expected, data = fragments[key]
            data.extend(acl)
        else:
            fragments.pop(key, None)
            if len(acl) < 4:
                continue
            size, cid = struct.unpack_from("<HH", acl)
            if cid != 4:
                continue
            expected, data = size, bytearray(acl[4:])

        if len(data) < expected:
            fragments[key] = expected, data
            continue
        fragments.pop(key, None)
        if len(data) == expected:
            connection.observe_att(data, received, timestamp)

    if selected_connection is not None:
        chosen = [item for item in connections if item.ordinal == selected_connection and item.att_messages]
        if not chosen:
            raise ValueError("The selected connection has no ATT messages in this capture.")
    else:
        chosen = [item for item in connections if item.saw_ring_hello]
    return {
        "selection": "manual" if selected_connection is not None else "galaxy_ring_hello",
        "att_connections": [item.ordinal for item in connections if item.att_messages],
        "truncated_acl_packets": truncated,
        "connections": [item.report() for item in chosen],
        "hrv_feature_150": ("decoded_pending_reference_validation" if any(item.hrv_records for item in chosen)
            else "not_observed_in_decoded_messages" if any(item.decoded_bulk_messages for item in chosen) else "not_decoded"),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path, help="Full Android BTSnoop log or bug-report ZIP.")
    parser.add_argument("--member", help="Exact snoop member path when the ZIP contains multiple candidates.")
    parser.add_argument("--connection", type=int, help="Override selection using an independently identified connection ordinal.")
    args = parser.parse_args()
    try:
        with snoop_file(args.capture, args.member) as stream:
            print(json.dumps(inspect(stream, args.connection), indent=2))
    except (ValueError, OSError, BadZipFile) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
