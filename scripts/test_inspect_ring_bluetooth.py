import io
from pathlib import Path
import struct
import tempfile
import unittest
from zipfile import ZipFile

from inspect_ring_bluetooth import BTSNOOP_H4_HEADER, RECORD, inspect, snoop_file


def acl(handle, data, boundary=2):
    return b"\x02" + struct.pack("<HH", handle | (boundary << 12), len(data)) + data


def l2cap(att):
    return struct.pack("<HH", len(att), 4) + att


def capture(events):
    return BTSNOOP_H4_HEADER + b"".join(
        RECORD.pack(len(packet), len(packet), received, 0, index * 1_000_000) + packet
        for index, (packet, received) in enumerate(events)
    )


class InspectRingBluetoothTest(unittest.TestCase):
    def test_selects_ring_and_reassembles_channel_10(self):
        hello = b"\x1b\x23\x00\xa7Galaxy Ring\x00synthetic-serial"
        reply = l2cap(b"\x1b\x23\x00\x0a\x0a\x3f\x96\x01")
        events = [
            (acl(1, l2cap(b"\x1b\x23\x00\x0a\x0a\x3f\x96")), 1),
            (acl(2, l2cap(hello)), 1),
            (acl(2, l2cap(b"\x52\x26\x00\x0a\x0a\x03")), 0),
            (acl(2, reply[:7]), 1),
            (acl(2, reply[7:], boundary=1), 1),
        ]
        result = inspect(io.BytesIO(capture(events)))
        self.assertEqual(result["att_connections"], [1, 2])
        ring, = result["connections"]
        self.assertEqual(ring["connection"], 2)
        self.assertEqual(ring["channel_10_messages"], {"phone_to_ring_write_command": 1,
                                                       "ring_to_phone_notification": 1})
        self.assertEqual(ring["channel_10_3f_reply_lengths"], {5: 1})
        self.assertEqual(result["hrv_feature_150"], "not_decoded")
        self.assertNotIn("synthetic-serial", str(result))

    def test_requires_discovery_and_valid_rr_flags(self):
        events = [
            (acl(1, l2cap(b"\x1b\x23\x00\xa7Galaxy Ring")), 1),
            (acl(1, l2cap(b"\x08\x01\x00\xff\xff\x03\x28")), 0),
            (acl(1, l2cap(b"\x09\x07\x0a\x00\x10\x0b\x00\x37\x2a")), 1),
            (acl(1, l2cap(b"\x1b\x0b\x00\x06\x4a\x00\x00\x00\x00")), 1),
            (acl(1, l2cap(b"\x1b\x0b\x00\x16\x4a\x00\x03")), 1),
            (acl(1, l2cap(b"\x1b\x0b\x00\x16\x4a")), 1),
        ]
        ring, = inspect(io.BytesIO(capture(events)))["connections"]
        self.assertTrue(ring["standard_heart_rate_characteristic_discovered"])
        self.assertEqual(ring["standard_heart_rate_notifications"], 3)
        self.assertEqual(ring["standard_rr_interval_notifications"], 1)

    def test_does_not_reuse_ring_identity_after_disconnect(self):
        events = [
            (acl(1, l2cap(b"\x1b\x23\x00\xa7Galaxy Ring")), 1),
            (b"\x04\x05\x04\x00\x01\x00\x13", 1),
            (acl(1, l2cap(b"\x1b\x23\x00\x0a\x0a\x3f\x96")), 1),
        ]
        result = inspect(io.BytesIO(capture(events)))
        self.assertEqual(result["att_connections"], [1, 2])
        ring, = result["connections"]
        self.assertEqual(ring["channel_10_messages"], {})

    def test_opens_only_the_full_snoop_member(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work, "report.zip")
            with ZipFile(path, "w") as archive:
                archive.writestr("bugreport.txt", "not a snoop log")
                archive.writestr("FS/data/log/bt/btsnoop_hci_2026-11-10_07-30-00.log", BTSNOOP_H4_HEADER)
                archive.writestr("FS/data/log/bt/btsnoop_hci.log.last", "older capture")
            with snoop_file(path, None) as stream:
                self.assertEqual(inspect(stream)["connections"], [])
            with self.assertRaisesRegex(ValueError, "must name a full"):
                with snoop_file(path, "bugreport.txt"):
                    self.fail("Opened unrelated bug-report data")


if __name__ == "__main__":
    unittest.main()
