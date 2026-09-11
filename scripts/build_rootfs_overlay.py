#!/usr/bin/env python3
"""Build euleros-rootfs-overlay.tar.gz that repairs the stripped base asset.

Why this exists
---------------
The committed base asset src/android/app/src/main/assets/euleros-minirootfs.tar.gz
is incomplete: SONAME symlinks survived (usr/lib64/libcurl.so.4) but the real
versioned libraries they point at were dropped (libcurl.so.4.7.0 is absent), and
whole packages are missing (libxml2, fontconfig, freetype, the entire pip tree).
Consequence on device: curl/git/wget/xz/python-lzma/pip were broken out of the box.

Rule 1 (automatic): every symlink in the base asset whose target is not in the
    base asset is restored from this live, hand-repaired rootfs.
Rule 2 (explicit):  small packages the base asset never shipped at all.

Run inside the repaired sandbox. Personal tooling (sign-apk, zipalign, aapt2,
JDK, apktool, jadx, node) is deliberately NOT included.
"""
import os, shutil, tarfile

BASE = "/tmp/minis-euleros/src/android/app/src/main/assets/euleros-minirootfs.tar.gz"
OUT  = "/tmp/minis-euleros/src/android/app/src/main/assets/euleros-rootfs-overlay.tar.gz"
STAGE = "/tmp/overlay"

EXPLICIT = [
    "/usr/local/bin/busybox",                       # 300+ applets: unzip cpio ar vi nc ...
    "/usr/local/bin/jq",
    "/usr/bin/ssh", "/usr/bin/scp", "/usr/bin/sftp", "/usr/bin/ssh-keygen",
    "/usr/lib64/libedit.so.0", "/usr/lib64/libedit.so.0.0.68",
    "/usr/lib64/libfontconfig.so.1", "/usr/lib64/libfontconfig.so.1.12.0",
    "/usr/lib64/libfreetype.so.6", "/usr/lib64/libfreetype.so.6.18.0",
    "/usr/lib64/libxml2.so.2", "/usr/lib64/libxml2.so.2.9.12",
    "/usr/bin/fc-cache", "/usr/bin/fc-cache-64", "/usr/bin/fc-list", "/usr/bin/fc-match",
    "/etc/fonts/fonts.conf",
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/dejavu/DejaVuSansMono.ttf",
]

def norm(n):
    return n.lstrip("./")

def collect():
    base = tarfile.open(BASE)
    names = {norm(m.name) for m in base.getmembers()}
    todo = []
    for m in base.getmembers():                                   # Rule 1
        if not m.issym():
            continue
        rel, tgt = norm(m.name), m.linkname
        if not tgt.startswith("/"):
            tgt = os.path.normpath(rel.rsplit("/", 1)[0] + "/" + tgt)
        tgt = norm(tgt)
        if tgt not in names and os.path.lexists("/" + tgt):
            todo.append(tgt)
    for p in EXPLICIT:                                             # Rule 2
        if os.path.lexists(p):
            todo.append(p[1:])
    return sorted(set(todo))

def stage(paths):
    shutil.rmtree(STAGE, ignore_errors=True)
    for p in paths:
        src, dst = "/" + p, os.path.join(STAGE, p)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.islink(src):
            os.symlink(os.readlink(src), dst)
        elif os.path.isdir(src):
            shutil.copytree(src, dst, symlinks=True)
        else:
            shutil.copy2(src, dst)

    # pip: the base asset ships site-packages with only README.txt, so `pip`
    # cannot even start (ModuleNotFoundError: pip). Restore a working copy to
    # the system site-packages that /usr/bin/pip3 imports from.
    src, dst_root = "/usr/local/lib/python3.9/site-packages", \
                    os.path.join(STAGE, "usr/lib64/python3.9/site-packages")
    os.makedirs(dst_root, exist_ok=True)
    n = 0
    for name in sorted(os.listdir(src)):
        if name == "pip" or name.startswith("pip-"):
            s, d = os.path.join(src, name), os.path.join(dst_root, name)
            (shutil.copytree(s, d, symlinks=True) if os.path.isdir(s)
             else shutil.copy2(s, d))
            n += 1
    print(f"pip restored: {n} entries -> usr/lib64/python3.9/site-packages/")
    for name in ("pip", "pip3", "pip3.9"):
        s = os.path.join("/usr/local/bin", name)
        if os.path.exists(s):
            shutil.copy2(s, os.path.join(STAGE, "usr/local/bin", name))

def pack(paths):
    total = 0
    with tarfile.open(OUT, "w:gz") as out:
        for root, dirs, files in os.walk(STAGE):
            arc = os.path.relpath(root, STAGE)
            arcname = "." if arc == "." else "./" + arc
            out.add(root, arcname=arcname, recursive=False)
            for f in sorted(files):
                full = os.path.join(root, f)
                out.add(full, arcname=os.path.join(arcname, f), recursive=False)
                total += os.lstat(full).st_size
    print(f"files={len(paths)} uncompressed={total/1048576:.1f}MB "
          f"gzip={os.path.getsize(OUT)/1048576:.1f}MB -> {OUT}")

if __name__ == "__main__":
    paths = collect()
    stage(paths)
    pack(paths)
    for p in paths[:8]:
        print("  ", p)
    print("  ...")
