# Reel TV（剧照桌面）

> English: Reel TV is an open-source home screen for Xiaomi/Redmi Android TVs. Home and the screensaver show this
> week's film and series stills from TMDB (with your own free TMDB key), Bing's image of the day or your own
> pictures. No root, nothing uninstalled. MIT licensed; parts of the code come from
> [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Tested on one TV so far.

给小米/红米电视用的简洁桌面。

- **首页**：全屏剧照，左上小时钟和日期，底栏上方一行「片名 · 来源」加三行简介。
- **背景来源**在电视上切换：TMDB 本周热门剧照、必应每日一图、场景壁纸、自己的海报。
- **屏保**：电视屏保时间到时轮播 TMDB 剧照，带片名和简介。
- **不 root、不卸载任何东西**：小米固件不允许第三方默认桌面，Reel TV 用辅助服务接管 Home 键，长按音量不受影响。
- **TMDB 用你自己的免费密钥**，在电视上填写；软件里不内置任何人的密钥。

**下载**：[Releases](https://github.com/tpxcer/reel-tv/releases)

目前只在 **Redmi MiTV-ASTP0（Android 9 / SDK 28，固件 25.6.6.OS2.0.19.0.PSTAATV）** 上实测，其他型号未测试。

## 安装（小米/红米）

在电视上打开 ADB 调试（小米：设置 → 账号与安全 → ADB 调试），电脑上用 `adb`（或本仓库的 `tools/tv.py`）：

```sh
adb connect <电视 IP>:5555
adb install -r Reel-TV-v0.2.0.apk
# 小米专用配置（见下方「小米/红米电视的配置」），放进桌面的主题目录
adb shell mkdir -p /sdcard/Android/data/io.github.tpxcer.reel/files
adb push config.json /sdcard/Android/data/io.github.tpxcer.reel/files/config.json
# 待机后自动补回服务所需的权限
adb shell pm grant io.github.tpxcer.reel android.permission.WRITE_SECURE_SETTINGS
# Home 键接管：先看现有辅助服务（小爱等），再把 HomeKeyService 加在后面
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services '<上一步的输出>:io.github.tpxcer.reel/.HomeKeyService'
adb shell settings put secure accessibility_enabled 1
# 待机强停后自动补回服务
adb shell cmd notification allow_listener io.github.tpxcer.reel/.HelperKeeper
# 只有下面这条输出为空或 null 时，才占用语音交互位（让 Home 立刻生效，不影响小爱）
adb shell settings get secure voice_interaction_service
adb shell settings put secure voice_interaction_service io.github.tpxcer.reel/.HomeVoiceService
adb shell am start -n io.github.tpxcer.reel/.MainActivity
```

- **不要**禁用原厂桌面 `com.mitv.tvhome`，也不要用 `set-home-activity` 把 Reel TV 设为默认桌面：这版固件不允许第三方默认桌面，会弹出空的选择器。
- 用真实遥控器试 Home、长按音量和小爱；ADB 注入的按键不算数。用完关闭 ADB 调试。
- 主题（`config.json`、壁纸、海报）也可以用 `tools/theme.sh themes/<名字>` 推送，见 [themes/README.md](themes/README.md)。
- 卸载：`adb uninstall io.github.tpxcer.reel`，系统会移除它的辅助服务和通知监听，Home 回到原厂桌面。**语音位不会自动清掉**（MiTV-ASTP0 实测），卸载后执行 `adb shell settings put secure voice_interaction_service ""`，或改回安装前的值。

## TMDB 密钥（必须自己申请）

本项目**不内置任何人的密钥**。不填密钥时不会访问 TMDB，选「TMDB 热门剧照」会先要求填写。

1. 在 [themoviedb.org](https://www.themoviedb.org/) 免费注册，到「设置 → API」申请，复制 **API Key**（32 位，适合用遥控器输入）或 **API 读访问令牌**（很长，适合推送文件）。
2. 填写到电视上，任选一种：
   - 长按右上角设置齿轮 →「简洁桌面设置」→「TMDB API 密钥」，或在「背景」里选「TMDB 热门剧照」时按提示填写。在输入框上按确定调出屏幕键盘，输完按键盘上的回车键（↵）保存；格式不对会提示，不会关闭。输入框对键盘声明为密码框（搜狗等中文键盘因此默认英文、不记词），但照常显示明文。小米自带的搜狗键盘有「跨屏输入」，长令牌可以用手机扫码输入。
   - 用 ADB 推送：把密钥写成一行的 `tmdb_key.txt`，放到 `/sdcard/Android/data/io.github.tpxcer.reel/files/`。桌面下次回到前台时把它搬进应用私有目录，并删除这个文件；推送一个空文件表示清除密钥。
3. 密钥只保存在应用私有目录，其他应用读不到；`tmdb_key.txt` 已写进 `.gitignore`，不要提交到仓库，也不要贴到 issue 或聊天里。
4. 设置页的密钥一行会显示上次取图结果（如 `OK 10` 或 `FAILED … HTTP 401`），方便判断密钥是否有效。

TMDB 要求署名：界面每张图都标注「图片来自 TMDB」，设置页注明 *This product uses the TMDB API but is not endorsed or certified by TMDB.* 使用前请阅读 TMDB 的 API 条款，以官方最新条款为准。

## 必应每日一图

使用的是必应首页图片的非公开接口（没有官方文档），可能随时变化或失效。图片版权归原作者，界面显示必应提供的标题和版权行。只在你选择它时联网。

## 小米/红米电视的配置

源码里的 `config.xml` 保持中性默认值，电视专用的值写在主题的 `config.json` 里。MiTV-ASTP0 用到的关键项：

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
  "home_idle_clock_minutes": 10,
  "module_cards": false
}
```

- **`key_filter` 必须是 `false`**：在这台电视上，任何过滤按键的辅助服务都会让长按音量只跳一格。代价是遥控器上依赖按键过滤的自定义按键不起作用。
- 删掉或重置这个 `config.json` 会退回 `config.xml` 的默认值（`key_filter` 为 `true`），长按音量会失效。
- 全部配置项见 [themes/README.md](themes/README.md)。

## 屏保

小米固件的屏保计时到点时不启动 Android 屏保，而是发广播 `com.duokan.intent.action.show.screensaver`。`home_idle_clock_minutes` 大于 0 时，Reel TV 接收它，用已保存的 TMDB 剧照轮播（片名、简介、署名）。小米相册（`com.xiaomi.tv.gallery`）也接收这个广播，会盖住 Reel TV 的屏保，需要的话请停用它（不要卸载）。

## 权限与风险

| 权限或做法 | 用途 | 怎么撤销 |
|---|---|---|
| `INTERNET` | 只用于上面的取图：填了 TMDB 密钥时访问 `api.tmdb.org`、`image.tmdb.org`，选了必应时访问 `cn.bing.com`，每天最多一次，不收集任何数据 | 不开 `trending_posters`、不选必应即不联网 |
| 辅助服务 `HomeKeyService`（读取前台窗口） | 看到原厂桌面出现就切回本桌面；只判断前台窗口属于哪个应用 | 系统设置 → 无障碍中关闭 |
| `WRITE_SECURE_SETTINGS`（安装时用 ADB 授予） | 小米待机会强停所有第三方应用，之后把辅助服务和语音位补回去 | `pm revoke io.github.tpxcer.reel android.permission.WRITE_SECURE_SETTINGS` |
| 通知监听 `HelperKeeper`（不读任何通知） | 待机强停后系统会立即重新绑定它，借此触发补回 | `cmd notification disallow_listener io.github.tpxcer.reel/.HelperKeeper` |
| 占用**空的**语音交互位 `HomeVoiceService` | 绕开 Android「按 Home 后 5 秒内禁止切换应用」的限制，Home 才能立刻回到本桌面；只在该位为空时占用，不影响小爱 | 把 `voice_interaction_service` 改回安装前的值 |

这些做法是针对这台固件验证过的，不保证适用于其他 Android 设备。

## 已知问题

- 小米「电视管家」（`com.xiaomi.mitv.tvmanager`）按云端规则，会在云视听极光、CIBN 酷喵等退到后台约 10 秒后强停它们，再打开会从头启动。这与桌面无关，电视上没有关闭开关。
- 小米应用商店更新系统应用时可能把你停用过的包重新启用，隔段时间用 `adb shell pm list packages -d` 看一下。

## 构建

需要 JDK 17、Android SDK 34（build-tools 34.0.0）：

```sh
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

- 默认包名 `io.github.tpxcer.reel`，可用 `-PlauncherApplicationId=<包名>` 换成别的；换了包名后，组件写成 `<包名>/io.github.tpxcer.reel.HomeKeyService` 这样的全名。
- 自己构建的版本用调试签名。Releases 里的 APK 用 Reel TV 的发布密钥签名（`-PreelSigning=<keystore.properties>`，密钥不在仓库里），所以自己构建的版本不能直接覆盖安装 Release 版，需要先卸载。每个 Release 都附 APK 的 SHA-256。
- Python 工具和测试：`python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`，然后 `python3 -m unittest discover -s tests -v`。

## 更多文档

- [themes/README.md](themes/README.md)：全部配置项和主题
- [docs/app-library.md](docs/app-library.md)：应用库
- [docs/sky.md](docs/sky.md)：随时间变化的天空背景
- [docs/watch-next.md](docs/watch-next.md)：继续观看
- [docs/modules.md](docs/modules.md)、[docs/specs/card.md](docs/specs/card.md)：模块卡片接口

## 安全问题

请在仓库的 [Security](https://github.com/tpxcer/reel-tv/security) 页面私下报告，不要发公开 issue。

## 许可

MIT，见 [LICENSE](LICENSE)。Reel TV 的部分代码、主题和工具来自 Gathin 的 [CleanYourTV](https://github.com/Gathin23/cleanyourtv)（MIT），按协议保留其版权声明（Copyright (c) 2026 Gathin）；其余部分版权归 tpxcer。
