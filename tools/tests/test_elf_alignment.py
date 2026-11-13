import struct
import sys
import tempfile
from pathlib import Path
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'python'))
from ros2android.elf_alignment import check_elf, check_apk


def elf(alignment=16384, offset=0, address=0):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, 183)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, address, 0, 120, 120, alignment)
    return bytes(data)


class ElfAlignmentTest(unittest.TestCase):
    def test_load_alignment_and_file_address_congruence_both_required(self):
        check_elf(elf())
        check_elf(elf(65536))
        for data in (elf(4096), elf(16384, offset=4096), elf(24576)):
            with self.assertRaisesRegex(ValueError, '16 KB'):
                check_elf(data)

    def test_truncated_wrong_machine_and_missing_loads_fail_closed(self):
        wrong_machine = bytearray(elf()); struct.pack_into('<H', wrong_machine, 18, 62)
        no_load = bytearray(elf()); struct.pack_into('<I', no_load, 64, 0)
        for data in (b'not ELF', elf()[:80], bytes(wrong_machine), bytes(no_load)):
            with self.assertRaises(ValueError):
                check_elf(data)

    def test_apk_checks_all_bundled_native_libraries(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'test.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libgood.so', elf())
                archive.writestr('lib/arm64-v8a/libbad.so', elf(4096))
            with self.assertRaisesRegex(ValueError, 'libbad.so'):
                check_apk(path)
