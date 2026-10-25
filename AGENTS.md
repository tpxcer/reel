# AGENTS.md

写给在本仓库工作的编程助手（Claude Code、Codex、Gemini CLI 等）。给人看的说明在 [README.md](README.md)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `launcher/` | 桌面应用：包名和 Java 命名空间都是 `io.github.tpxcer.reel`，纯 Java 加 AndroidX，`minSdk` 26 |
| `themes/` | 主题（`config.json`、壁纸、截图）；全部配置项在 [themes/README.md](themes/README.md) |
| `tools/` | `tv.py`（ADB 客户端）、`b64push.py`、`adb-closed.py`、`theme.sh`/`theme.ps1`、`race_calendar.py`、`scenes_wallpapers.py`；用法写在各脚本开头 |
| `tests/` | Python `unittest` 测试 |
| `docs/` | 功能说明 |
| `.github/workflows/ci.yml` | 测试、构建和 APK 体积门槛 |

## 构建与检查

- 桌面：JDK 17、Android SDK 34。在 `launcher/` 里运行 `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`，lint 必须 0 错误。
- APK 体积：`launcher/app/build/outputs/apk/release/app-release.apk` 必须小于 524,288 B（512 KB），CI 会检查。
- Python：`python3 -m unittest discover -s tests -v`。
- 改布局时同步改 `layout-iw`，`tests/test_hebrew_resources.py` 会检查。

## 在电视上操作

- 系统包只停用、不卸载（`pm disable-user --user 0`）；不 root、不刷机；不启动任何工厂或测试应用。
- 小米/红米：不要禁用原厂桌面 `com.mitv.tvhome`，不要用 `set-home-activity`；`config.json` 里的 `key_filter` 保持 `false`。
- Home、长按音量、小爱这些遥控行为必须请人用真实遥控器验证，ADB 注入的按键只算预检。`am force-stop io.github.tpxcer.reel` 会关掉辅助服务，`HelperKeeper` 会补回。
- 截图前确认前台不是小米设置的悬浮窗（会显示账号）。
- 用完关闭 ADB，并用 `tools/adb-closed.py <电视 IP>` 确认端口拒绝连接。

## 不要提交

- 签名密钥和口令（`*.jks`、`keystore.properties`）、TMDB 密钥（`tmdb_key.txt`）、ADB 私钥（`tools/adbkey/`）、截图（`shots/`）、备份和工作笔记。
- 设备标识：IP、Wi-Fi 名、账号、序列号、MAC 地址。
- 编译好的 APK：发布走 GitHub Releases。

## 代码约定

- 新配置项默认关闭：在 `config.xml` 写注释，在 `Config.java` 里同时读资源和 `config.json`，并在 `themes/README.md` 加一行。
- 能在 JVM 上测的逻辑加单元测试，放在 `launcher/app/src/test/java/io/github/tpxcer/reel/`。
- 界面文字放 `strings.xml`（英文默认），同时补 `values-zh-rCN` 和 `values-zh-rTW`。
