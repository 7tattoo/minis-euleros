# MinisEulerOS

**EulerOS 定制版 · OpenMinis v1.13 分支**

> 本项目基于 [OpenMinis](https://github.com/OpenMinis/OpenMinis) v1.13 分叉（fork）而来，
> 将 Linux 运行环境替换为**华为云 EulerOS 2.0（aarch64 · glibc）**，并对 Android 工程做了 euleros 化定制与共存修复。
>
> 应用标识：`com.miniseuleros.app`（与原版可共存安装）

---

## ✨ 功能亮点

- **真 Linux 跑在手机上**：通过 proot 沙箱在 Android 上原生运行 EulerOS 2.0 完整 rootfs（非模拟器），bash / yum / glibc 生态完整可用
- **内置开发工具链（离线可用）**：Python 3.9、GCC 10.3、Git 2.33、FFmpeg 4.2，以及 Gradle 8.5 + JDK 17 + Android SDK——手机上即可直接编译 Android APK
- **端侧 AI Agent**：内置 AI 助手深度集成系统能力，可操作文件、执行命令、编写代码、构建应用、处理音视频
- **挂载外部目录**：通过系统 SAF 授权把手机目录挂载进 Linux 环境（`/var/minis/mounts/`），原生读写，绕过 SAF 限制
- **与原版 OpenMinis 共存**：修复 Android abstract socket 命名冲突，两台应用可同时安装、同时运行，互不干扰
- **精简定制**：移除 rclone 远程备份等冗余模块，聚焦核心体验
- **隐私优先**：全部计算与数据处理均在设备本地完成，不上传云端

## 🚀 快速开始

1. 安装 APK（`app/build/outputs/apk/debug/` 或 Release 包）
2. 启动应用，完成 EulerOS rootfs 部署（首次启动自动完成）
3. 授予所需权限（Shizuku / 存储 / 通知）
4. 与你的端侧 AI Agent 对话

## 🔨 本地构建

```bash
cd OpenMinis-1.13-euleros
gradle assembleDebug --no-daemon -Dorg.gradle.vfs.watch=false
```

构建产物位于 `app/build/outputs/apk/debug/`。

## 📄 许可证

本项目继承上游 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 的 **GPL v3** 许可证，详见 [LICENSE](LICENSE) 与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

- 上游仓库：https://github.com/OpenMinis/OpenMinis
- OpenMinis 官网：https://openminis.app