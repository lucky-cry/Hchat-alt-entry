#!/usr/bin/env python3
"""在 DEX 中查找引用指定字符串常量（const-string / const-string/jumbo）的类与方法。

用于微信升级后确认「锚点字符串还在，但所属类是否变化」。

用法:
    python3 dex_string_refs.py <apk或dex> <字符串1> [字符串2 ...]
"""
import sys, zipfile, struct


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def mutf8(data, off):
    out = []
    while data[off] != 0:
        c = data[off]
        if c < 0x80:
            out.append(c)
            off += 1
        elif c & 0xE0 == 0xC0:
            out.append(((c & 0x1F) << 6) | (data[off + 1] & 0x3F))
            off += 2
        elif c & 0xF0 == 0xE0:
            out.append(((c & 0x0F) << 12) | ((data[off + 1] & 0x3F) << 6) | (data[off + 2] & 0x3F))
            off += 3
        else:
            off += 1
    return "".join(map(chr, out))


class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off) = struct.unpack_from("<II", data, 0x38)
        (self.type_ids_size, self.type_ids_off) = struct.unpack_from("<II", data, 0x40)
        (self.method_ids_size, self.method_ids_off) = struct.unpack_from("<II", data, 0x58)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from("<II", data, 0x60)
        self._strings = {}
        self._types = {}

    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4 * idx)[0]
        _, p = uleb128(self.d, off)
        s = mutf8(self.d, p)
        self._strings[idx] = s
        return s

    def type_name(self, idx):
        if idx in self._types:
            return self._types[idx]
        sidx = struct.unpack_from("<I", self.d, self.type_ids_off + 4 * idx)[0]
        s = self.string(idx=sidx)
        self._types[idx] = s
        return s

    def method(self, midx):
        cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8 * midx)
        return self.type_name(cls_idx), self.string(name_idx)

    def string_index(self, target):
        lo, hi = 0, self.string_ids_size - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            s = self.string(mid)
            if s == target:
                return mid
            if s < target:
                lo = mid + 1
            else:
                hi = mid - 1
        return None

    def code_refs(self, target_index):
        """返回引用该字符串的 (类描述符, 方法名, 方法描述符)。"""
        hits = []
        d = self.d
        for i in range(self.class_defs_size):
            base = self.class_defs_off + 32 * i
            class_idx = struct.unpack_from("<I", d, base)[0]
            class_data_off = struct.unpack_from("<I", d, base + 24)[0]
            if class_data_off == 0:
                continue
            cls = self.type_name(class_idx)
            off = class_data_off
            sf, off = uleb128(d, off)
            inf, off = uleb128(d, off)
            dm, off = uleb128(d, off)
            vm, off = uleb128(d, off)
            for _ in range(sf + inf):
                _, off = uleb128(d, off)
                _, off = uleb128(d, off)
            for count in (dm, vm):
                midx = 0
                for _ in range(count):
                    diff, off = uleb128(d, off)
                    midx += diff
                    _, off = uleb128(d, off)  # access_flags
                    code_off, off = uleb128(d, off)
                    if code_off == 0:
                        continue
                    insns_size = struct.unpack_from("<I", d, code_off + 12)[0]
                    start = code_off + 16
                    p = 0
                    while p < insns_size:
                        unit = struct.unpack_from("<H", d, start + 2 * p)[0]
                        op = unit & 0xFF
                        if op == 0x1A and p + 1 < insns_size:  # const-string
                            idx = struct.unpack_from("<H", d, start + 2 * p + 2)[0]
                            if idx == target_index:
                                _, name = self.method(midx)
                                hits.append((cls, name))
                            p += 2
                            continue
                        if op == 0x1B and p + 2 < insns_size:  # const-string/jumbo
                            idx = struct.unpack_from("<I", d, start + 2 * p + 2)[0]
                            if idx == target_index:
                                _, name = self.method(midx)
                                hits.append((cls, name))
                            p += 3
                            continue
                        p += 1
        return hits


def dex_files(path):
    if path.lower().endswith(".apk") or zipfile.is_zipfile(path):
        z = zipfile.ZipFile(path)
        return [(n, z.read(n)) for n in z.namelist() if n.endswith(".dex")]
    return [(path, open(path, "rb").read())]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    path, targets = sys.argv[1], sys.argv[2:]
    for name, data in dex_files(path):
        dex = Dex(data)
        for t in targets:
            idx = dex.string_index(t)
            if idx is None:
                print(f"[{name}] 字符串不存在: {t!r}")
                continue
            hits = dex.code_refs(idx)
            uniq = sorted(set(hits))
            if not uniq:
                print(f"[{name}] {t!r}: 无代码引用（可能只作数据使用）")
            for cls, meth in uniq[:12]:
                print(f"[{name}] {t!r} <- {cls}.{meth}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
