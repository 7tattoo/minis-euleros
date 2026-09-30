import os
import sys
import zipfile

src_path, target_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

src = zipfile.ZipFile(src_path)
src_libs = {}
for zi in src.infolist():
    if zi.filename.startswith("lib/"):
        src_libs[zi.filename] = src.read(zi.filename)
src.close()
print("source native libs: %d" % len(src_libs))
for k in sorted(src_libs):
    print("  %-58s %10d" % (k, len(src_libs[k])))

tgt = zipfile.ZipFile(target_path)
tgt_entries = []
dropped_lib = 0
dropped_meta = 0
for zi in tgt.infolist():
    if zi.filename.startswith("lib/"):
        dropped_lib += 1
        continue
    if zi.filename.startswith("META-INF/"):
        dropped_meta += 1
        continue
    data = tgt.read(zi.filename)
    tgt_entries.append((zi, data, zi.compress_type, zi.date_time, zi.external_attr))
tgt.close()
print("dropped from target: %d lib/, %d META-INF/" % (dropped_lib, dropped_meta))
print("keeping: %d entries" % len(tgt_entries))

with zipfile.ZipFile(out_path, "w") as out:
    for zi, data, cmode, dt, attrs in tgt_entries:
        info = zipfile.ZipInfo(zi.filename, date_time=dt)
        info.compress_type = cmode
        info.external_attr = attrs
        out.writestr(info, data)
    for name in sorted(src_libs):
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = 0o755 << 16
        out.writestr(info, src_libs[name])

with zipfile.ZipFile(out_path) as z:
    bad = z.testzip()
    assert bad is None, "corrupt entry: %s" % bad
    libs = [n for n in z.namelist() if n.startswith("lib/")]
    metas = [n for n in z.namelist() if n.startswith("META-INF/")]
    print("OUT entries=%d lib=%d meta=%d" % (len(z.namelist()), len(libs), len(metas)))
    assert len(libs) == len(src_libs)
    assert not metas, metas
    for name in libs:
        ok = len(z.read(name)) == len(src_libs[name])
        print("  %-58s %s" % (name, "OK" if ok else "SIZE MISMATCH"))
        assert ok
print("out size: %d" % os.path.getsize(out_path))
