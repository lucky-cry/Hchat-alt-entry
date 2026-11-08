#!/usr/bin/env python3
"""检查模块用于 DexKit 定位的字符串锚点是否仍存在于指定微信 APK 的 dex 中。
用法: python3 check_anchors.py <wechat.apk> <anchors.txt>
"""
import sys, zipfile

def load_anchors(path):
    out=[]
    for line in open(path, encoding='utf-8'):
        s=line.strip()
        if s and not s.startswith('#'):
            out.append(s)
    return out

def main():
    apk, anchor_file = sys.argv[1], sys.argv[2]
    anchors = load_anchors(anchor_file)
    z = zipfile.ZipFile(apk)
    dex = [n for n in z.namelist() if n.endswith('.dex')]
    found = {a: [] for a in anchors}
    for name in dex:
        data = z.read(name)
        for a in anchors:
            if not found[a] and a.encode('utf-8') in data:
                found[a].append(name)
    missing = [a for a in anchors if not found[a]]
    print(f"锚点 {len(anchors)} 个，dex {len(dex)} 个；缺失 {len(missing)} 个")
    for a in missing:
        print("  缺失:", a)
    return 0 if not missing else 1

if __name__ == "__main__":
    sys.exit(main())
