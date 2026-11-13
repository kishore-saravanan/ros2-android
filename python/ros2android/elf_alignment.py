"""Check target ELF load-segment alignment without executing native code."""
import struct
import zipfile

PAGE_SIZE = 16384


def check_elf(data, label='ELF'):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'{label}: expected a little-endian ELF64 file')
    if struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError(f'{label}: expected AArch64')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    if size < 56 or count == 0 or offset + size * count > len(data):
        raise ValueError(f'{label}: invalid ELF program-header table')
    loads = 0
    for index in range(count):
        kind, _, position, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + index * size)
        if kind == 1:  # PT_LOAD
            loads += 1
            if alignment < PAGE_SIZE or alignment & (alignment - 1) or (position - address) % PAGE_SIZE:
                raise ValueError(f'{label}: load segment is not 16 KB aligned; rebuild its producer')
    if not loads:
        raise ValueError(f'{label}: no ELF load segments')


def check_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        names = [name for name in archive.namelist() if name.startswith('lib/arm64-v8a/') and name.endswith('.so')]
        if not names:
            raise ValueError('APK has no ARM64 native libraries')
        for name in names:
            check_elf(archive.read(name), name)
        return len(names)


def check_tree(root):
    count = 0
    for path in sorted(root.rglob('*')):
        if path.is_file() and not path.is_symlink():
            with path.open('rb') as stream:
                magic = stream.read(4)
            if magic == b'\x7fELF':
                check_elf(path.read_bytes(), str(path.relative_to(root)))
                count += 1
    if not count:
        raise ValueError('No target ELF files found')
    return count
