# Reel TV（剧照桌面）

> English: Reel TV is an independent home screen for Xiaomi/Redmi TVs, built in part on code from
> [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT). It has an Aerial-style home, optional TMDB / Bing
> backdrops (network, off by default, your own TMDB key) and a TMDB screensaver.

Reel TV 是一个独立项目：给小米/红米电视用的简洁桌面，首页和屏保用每天更新的影视剧照。它的部分代码、工具和安全约束（禁用不卸载、不 root、Home 保护等）来自 CleanYourTV（MIT），见 [README](../README.md) 与 [AGENTS.md](../AGENTS.md)。本页写 Reel TV 自己的功能、安装和注意事项。

## 与 CleanYourTV 的区别

- **桌面会联网**（CleanYourTV 不联网）：只用于取背景图，默认全部关闭。
  - TMDB 热门剧照：`trending_posters` 打开并填写**你自己的** TMDB 密钥后，每天最多一次访问 `api.tmdb.org`、`image.tmdb.org`。
  - 必应每日一图：只在「桌面设置 → 背景」选了它时，每天最多一次访问 `cn.bing.com`。
  - 不收集、不上传任何数据，没有统计。
- **Aerial 风格首页**：全屏大图，左上小时钟，底栏上方一行「片名 · 来源」加三行简介；背景来源可在电视上切换（TMDB / 必应 / 场景壁纸 / 我的海报）。
- **TMDB 屏保**：小米固件的屏保计时到点时不启动 Android 屏保，而是发广播 `com.duokan.intent.action.show.screensaver`；`home_idle_clock_minutes` 大于 0 时本桌面接收它，用已保存的 TMDB 剧照轮播（片名、简介、署名）。
- **小米/红米 Home 兼容**：固件不允许第三方默认桌面，Home 由辅助服务接管；另有待机后自动补回服务、语音交互位豁免、顶栏「原厂桌面」入口。见下方「权限与风险」。
- **打开应用的方式**：有标准桌面入口（LAUNCHER）的应用按该入口启动，回到它时接着原来的页面，不会重开启动页。
- **包名** `io.github.tpxcer.reel`（Java 类名仍是 `com.gathin.tvhome.*`），可以和 CleanYourTV 同时安装。组件要写全，例如 `io.github.tpxcer.reel/com.gathin.tvhome.MainActivity`。仓库里沿用自 CleanYourTV 的故障排查、`examples/` 和 `docs/devices/` 中的 `com.gathin.tvhome` 是 CleanYourTV 的包名，照做时换成 `io.github.tpxcer.reel`，短写的 `.HomeKeyService` 要写全为 `com.gathin.tvhome.HomeKeyService`。
- 本仓库 CI 的 APK 体积门槛为 512 KB（CleanYourTV 为 480 KB）；CleanYourTV 维护者审 PR 用的 maintainer-bot 工作流不在本仓库。

## 已测试设备

只在 **Redmi MiTV-ASTP0（Android 9 / SDK 28，固件 25.6.6.OS2.0.19.0.PSTAATV）** 上实测。其他型号未测试，请先按 README 中的流程备份。

## 安装 Release 版（小米/红米）

在电视上打开 ADB 调试（小米：设置 → 账号与安全 → ADB 调试），电脑上用 `adb`（或本仓库的 `tools/tv.py`）：

```sh
adb connect <电视 IP>:5555
adb install -r Reel-TV-v0.1.1.apk
# 小米专用配置（见下方「小米/红米电视的配置」），放进桌面的主题目录
adb shell mkdir -p /sdcard/Android/data/io.github.tpxcer.reel/files
adb push config.json /sdcard/Android/data/io.github.tpxcer.reel/files/config.json
# 待机后自动补回服务所需的权限
adb shell pm grant io.github.tpxcer.reel android.permission.WRITE_SECURE_SETTINGS
# Home 键接管：先看现有辅助服务（小爱等），再把 HomeKeyService 加在后面
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services '<上一步的输出>:io.github.tpxcer.reel/com.gathin.tvhome.HomeKeyService'
adb shell settings put secure accessibility_enabled 1
# 待机强停后自动补回服务
adb shell cmd notification allow_listener io.github.tpxcer.reel/com.gathin.tvhome.HelperKeeper
# 只有下面这条输出为空或 null 时，才占用语音交互位（让 Home 立刻生效，不影响小爱）
adb shell settings get secure voice_interaction_service
adb shell settings put secure voice_interaction_service io.github.tpxcer.reel/com.gathin.tvhome.HomeVoiceService
adb shell am start -n io.github.tpxcer.reel/com.gathin.tvhome.MainActivity
```

- **不要**禁用原厂桌面 `com.mitv.tvhome`，也不要用 `set-home-activity` 把 Reel TV 设为默认桌面：这版固件不允许第三方默认桌面，会弹出空的选择器。`launcher/build-and-install.sh`（来自 CleanYourTV）面向能设第三方默认桌面的电视，小米/红米请用上面的手动步骤。
- 用真实遥控器试 Home、长按音量和小爱；ADB 注入的按键不算数。用完关闭 ADB 调试。
- 卸载：`adb uninstall io.github.tpxcer.reel`，系统会移除它的辅助服务和通知监听，Home 回到原厂桌面。**语音位不会自动清掉**（MiTV-ASTP0 实测），卸载后执行 `adb shell settings put secure voice_interaction_service ""`，或改回安装前的值。

## TMDB 密钥（必须自己申请）

本项目**不内置任何人的密钥**。不填密钥时不会访问 TMDB，选「TMDB 热门剧照」会先要求填写。

1. 在 [themoviedb.org](https://www.themoviedb.org/) 免费注册，到「设置 → API」申请，复制 **API Key**（32 位，适合用遥控器输入）或 **API 读访问令牌**（很长，适合推送文件）。
2. 填写到电视上，任选一种：
   - 长按右上角设置齿轮 →「简洁桌面设置」→「TMDB API 密钥」，或在「背景」里选「TMDB 热门剧照」时按提示填写。在输入框上按确定调出屏幕键盘，输完按键盘上的回车键（↵）保存；格式不对会提示，不会关闭。输入框对键盘声明为密码框（搜狗等中文键盘因此默认英文、不记词），但照常显示明文。小米自带的搜狗键盘有「跨屏输入」，长令牌可以用手机扫码输入。
   - 用 ADB 推送：把密钥写成一行的 `tmdb_key.txt`，放到 `/sdcard/Android/data/<包名>/files/`。桌面下次回到前台时把它搬进应用私有目录，并删除这个文件；推送一个空文件表示清除密钥。
3. 密钥只保存在应用私有目录，其他应用读不到；`tmdb_key.txt` 已写进 `.gitignore`，不要提交到仓库，也不要贴到 issue 或聊天里。
4. 设置页的密钥一行会显示上次取图结果（如 `OK 10` 或 `FAILED … HTTP 401`），方便判断密钥是否有效。

TMDB 要求署名：界面每张图都标注「图片来自 TMDB」，设置页注明 *This product uses the TMDB API but is not endorsed or certified by TMDB.* 使用前请阅读 TMDB 的 API 条款，以官方最新条款为准。

## 必应每日一图

使用的是必应首页图片的非公开接口（没有官方文档），可能随时变化或失效。图片版权归原作者，界面显示必应提供的标题和版权行。只在你选择它时联网。

## 小米/红米电视的配置

源码里的 `config.xml` 保持中性默认值，本机专用的值写在主题的 `config.json` 里（`tools/theme.sh` 推送）。MiTV-ASTP0 用到的关键项：

```json
{
  "key_filter": false,
  "restore_key_services": true,
  "stock_home_redirect": "com.mitv.tvhome",
  "live_tv_package": "com.xiaomi.mitv.tvplayer",
  "input_action": "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY",
  "input_sources": ["HW5=23", "HW6=24", "HW7=25", "HW1=2"],
  "cinematic_home": true,
  "trending_posters": true,
  "home_idle_clock_minutes": 10
}
```

- **`key_filter` 必须是 `false`**：在这台电视上，任何过滤按键的辅助服务都会让长按音量只跳一格。代价是遥控器上依赖按键过滤的自定义按键不起作用。
- 删掉或重置这个 `config.json` 会退回 `config.xml` 的默认值（`key_filter` 为 `true`），长按音量会失效。

## 权限与风险

| 权限或做法 | 用途 | 怎么撤销 |
|---|---|---|
| `INTERNET` | 只用于上面的取图 | 不开 `trending_posters`、不选必应即不联网 |
| 辅助服务 `HomeKeyService`（读取前台窗口） | 看到原厂桌面出现就切回本桌面；只判断前台窗口属于哪个应用 | 系统设置 → 无障碍中关闭 |
| `WRITE_SECURE_SETTINGS`（安装时用 ADB 授予） | 小米待机会强停所有第三方应用，之后把辅助服务和语音位补回去 | `pm revoke <包名> android.permission.WRITE_SECURE_SETTINGS` |
| 通知监听 `HelperKeeper`（不读任何通知） | 待机强停后系统会立即重新绑定它，借此触发补回 | `cmd notification disallow_listener <包名>/com.gathin.tvhome.HelperKeeper` |
| 占用**空的**语音交互位 `HomeVoiceService` | 绕开 Android「按 Home 后 5 秒内禁止切换应用」的限制，Home 才能立刻回到本桌面；只在该位为空时占用，不影响小爱 | 把 `voice_interaction_service` 改回安装前的值 |

这些做法是针对这台固件验证过的，不保证适用于其他 Android 设备。

## 已知问题

- 小米「电视管家」（`com.xiaomi.mitv.tvmanager`）按云端规则，会在云视听极光、CIBN 酷喵等退到后台约 10 秒后强停它们，再打开会从头启动。这与桌面无关，电视上没有关闭开关。

## 构建

需要 JDK 17、Android SDK 34。默认包名 `io.github.tpxcer.reel`，可用 `launcherApplicationId` 换成别的：

```sh
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

自己构建的版本用调试签名。Releases 里的 APK 用 Reel TV 的发布密钥签名（`-PreelSigning=<keystore.properties>`，密钥不在仓库里），所以自己构建的版本不能直接覆盖安装 Release 版，需要先卸载。每个 Release 都附 APK 的 SHA-256。

## 许可

MIT，见 [LICENSE](../LICENSE)。Reel TV 新增的部分版权归 tpxcer；来自 CleanYourTV 的代码按 MIT 要求保留其作者的版权声明（Copyright (c) 2026 Gathin）。
