#!/usr/bin/env python3
"""列出 DEX 中某个类的方法签名（含参数/返回类型），用于微信升级后核对 Hook 目标。

用法:
    python3 dex_class_methods.py <apk> <类描述符或类名> [参数过滤关键字]
示例:
    python3 dex_class_methods.py wechat.apk Lcom/tencent/mm/storage/g9; AddMsg
"""
import sys, zipfile, struct
from dex_string_refs import Dex, uleb128


def methods_of(data, dex, desc, needle=None):
    out = []
    pido = struct.unpack_from("<I", data, 0x4C)[0]
    for i in range(dex.class_defs_size):
        base = dex.class_defs_off + 32 * i
        if dex.type_name(struct.unpack_from("<I", data, base)[0]) != desc:
            continue
        off = struct.unpack_from("<I", data, base + 24)[0]
        sf, off = uleb128(data, off)
        inf, off = uleb128(data, off)
        dm, off = uleb128(data, off)
        vm, off = uleb128(data, off)
        for _ in range(sf + inf):
            _, off = uleb128(data, off)
            _, off = uleb128(data, off)
        for count in (dm, vm):
            midx = 0
            for _ in range(count):
                diff, off = uleb128(data, off)
                midx += diff
                acc, off = uleb128(data, off)
                code, off = uleb128(data, off)
                _, pidx, nameidx = struct.unpack_from("<HHI", data, dex.method_ids_off + 8 * midx)
                _, ret, poff = struct.unpack_from("<III", data, pido + 12 * pidx)
                params = []
                if poff:
                    n = struct.unpack_from("<I", data, poff)[0]
                    params = [dex.type_name(struct.unpack_from("<H", data, poff + 4 + 2 * k)[0])
                              for k in range(n)]
                line = f"{dex.string(nameidx)}({', '.join(params)}) -> {dex.type_name(ret)} code={code != 0}"
                if needle is None or any(needle in p for p in params):
                    out.append(line)
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    apk, cls = sys.argv[1], sys.argv[2]
    needle = sys.argv[3] if len(sys.argv) > 3 else None
    if not cls.startswith("L"):
        cls = "L" + cls.replace(".", "/") + ";"
    z = zipfile.ZipFile(apk)
    for name in [n for n in z.namelist() if n.endswith(".dex")]:
        data = z.read(name)
        dex = Dex(data)
        found = methods_of(data, dex, cls, needle)
        for line in found:
            print(f"[{name}] {cls[1:-1].replace('/', '.')} {line}")
        if found:
            return 0
    print("未找到该类或没有匹配方法")
    return 1


if __name__ == "__main__":
    sys.exit(main())
