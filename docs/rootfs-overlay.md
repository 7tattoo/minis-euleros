# 沙箱 rootfs 资产缺陷与 overlay 修复

## 现象（装机即坏，无需任何操作）
| 工具 | 报错 | 缺的东西 |
|---|---|---|
| `curl` / `git` | `cannot open shared object file: libcurl.so.4` | `libcurl.so.4.7.0` |
| `xz` / `python3 -c "import lzma"` | `liblzma.so.5` | `liblzma.so.5.2.5` |
| `wget` | `libmetalink.so.3` | `libmetalink.so.3.1.0` |
| `wget`（装好 metalink 后） | `libgpgme.so.11` / `libnettle.so.8` | gpgme / nettle / gnutls 全链 |
| `pip` | `ModuleNotFoundError: pip._vendor.distlib` | 整个 pip 包目录 |
| `file` / `free` | `libmagic.so.1` / `libsystemd.so.0` | libmagic / systemd-libs |
| Java / `jadx` | `Fontconfig head is null` | fontconfig + freetype + libxml2 + 字体 |

## 根因：base 资产被砍掉了真实载荷

`src/android/app/src/main/assets/euleros-minirootfs.tar.gz` 里有 SONAME 软链接，
但链接指向的版本化真实库不在包里：

```
包里 有  usr/lib64/libcurl.so.4        -> libcurl.so.4.7.0
包里没有 usr/lib64/libcurl.so.4.7.0          <-- 缺
包里 有  usr/lib64/liblzma.so.5          -> liblzma.so.5.2.5
包里没有 usr/lib64/liblzma.so.5.2.5          <-- 缺
包里 有  usr/lib64/libmetalink.so.3      -> libmetalink.so.3.1.0
包里没有 usr/lib64/libmetalink.so.3.1.0      <-- 缺
```

整包缺失的：libxml2、fontconfig、freetype、openssh 客户端、busybox、
以及 `usr/lib64/python3.9/site-packages/`（包里只有一个 README.txt，
所以 pip 根本没有可导入的文件）。

**解包代码不是问题。** 用真 tar 解 base 包后与实机 rootfs 全量对比：
2761 个条目中 2746 个完好，仅 15 个"悬空软链接"未落到磁盘，而这 15 个的
目标本来就不在包里 —— 属于 `RootfsManager.extractTar()` 里
`Files.createSymbolicLink` 对无效目标静默失败，不造成实际损坏。

## 修复方式：不重打包 base，改成 overlay 叠加

新增资产 `euleros-rootfs-overlay.tar.gz`（11.1 MB / 1057 个条目），
`RootfsManager.applyOverlayIfNeeded()` 负责叠加，**两条路径都会走**：

- 全新安装：解完 base 立即叠加；
- 已安装的老用户：`isInstalled` 为真时也补打一次（否则永远拿不到修复）。

叠加是非破坏性的（`extractTar` 只写入/覆盖，不删除），并用 stamp 文件
`euleros-rootfs/.rootfs-overlay` 记录版本号 `ROOTFS_OVERLAY_VERSION`，
只打一次。overlay 资产缺失或叠加失败只记日志，不会阻断 rootfs 安装。

**不需要清数据。** 清数据会连带删掉 rootfs 里的 `/var/minis-euleros`
（workspace / memory / attachments 都在里面）。升级 APK 后下次启动沙箱即自动生效。

overlay 内容按两条规则从"手工修好后的沙箱"采集：
1. **规则一（自动）**：base 包里每个目标缺失的软链接 → 从修好的 rootfs
   取真实 .so（31 个，含 libcurl/liblzma/libmetalink/libgpgme/libcom_err/
   libgnutls/libnettle/libgmp/libp11-kit/libkrb5/libssh/libldap/libunistring/
   libpcre 等，共 11.5 MB）。
2. **规则二（显式）**：base 从未包含的小型工具包 —— busybox（300+ applet：
   unzip/cpio/ar/vi/nc…）、jq、openssh 客户端（ssh/scp/sftp + libedit）、
   fontconfig + freetype + libxml2、DejaVu 字体、fc-* 命令、以及一个完整
   可用的 pip（放到 base 的 `/usr/bin/pip3` 会去导入的 site-packages 下）。

刻意**不包含**：`sign-apk`（内含用户 keystore 口令）、`zipalign`、`aapt2`、
JDK、apktool/jadx、Node —— 这些按需现装，不进公共资产。

## 重建 overlay
在已修好的沙箱里执行（HCE/EulerOS aarch64 rootfs 本身即可）：

```sh
python3 scripts/build_rootfs_overlay.py
```

产物写入 `src/android/app/src/main/assets/euleros-rootfs-overlay.tar.gz`，
与 base 包用同一 tar 结构（`./usr/...`、无长文件名扩展），
`extractTar()` 无需任何改动即可处理。

## 注意
- 每次更新 base 资产后应重跑一次脚本：base 新增的悬空链接会被规则一自动补齐。
- overlay 里是真实库文件，不含 HCE 的 `dim` 完整性清单；当前沙箱无
  `dim` 校验守护进程，运行不受影响。若后续启用，需要连 hash 一起补。
- iOS 侧（`RootfsManager.swift`）若用同一 rootfs 资产，需要同样叠加逻辑。
