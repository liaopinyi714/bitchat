# bitchat Android 分支

本目录基于 [官方 Android bitchat v2.0.1](https://github.com/permissionlesstech/bitchat-android/tree/v2.0.1)，保留 Kotlin / Compose 界面与本地 mesh 功能，接入本仓库的 Cloudflare 命名频道。

这是独立开源分支。手机应用名为 bitchat，安装标识为 `xyz.liaopinyi714.bitchat`，可与官方应用并存。地图、geohash 在线聊天和附近地理笔记已从手机用户流程移除；本分支不连接原版地理频道，不使用官方发行 APK 作为自身更新。

## 使用和功能

- 手机最低 Android 8.0。BLE 和受支持设备上的 Wi-Fi Aware 保留；在线频道不依赖定位权限。旧 Android 的本地发现限制见 [权限说明](../docs/ANDROID.md)。
- 输入频道名称创建或加入，没有地图、固定房间名单或全站搜索。公开频道正文可被中继读取；密码频道使用 PBKDF2-HMAC-SHA256 + AES-256-GCM。
- Noise 私聊、验证、回执、收藏、拉黑、图片、文件、语音和 panic 保留上游流程。在线传输及后台表现仍需真实设备验收。
- 手机在线中继默认使用 `chat.123456714.xyz`，由仓库所有者自行部署。Wear 模块保留上游本地功能，尚未接入在线频道。

完整说明：[使用](../docs/USAGE.md)、[功能与验收](../docs/FEATURES.md)、[部署](../docs/DEPLOYMENT.md)、[协议](../docs/PROTOCOL.md)、[安全边界](../SECURITY.md)。

## 构建与检查

使用 JDK 21；Android SDK 和构建工具以 `gradle/libs.versions.toml` 为准。在本目录运行：

```sh
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest lintDebug clientRewriteContractTest --no-daemon
```

Windows 使用 `gradlew.bat`，建议用短 ASCII 路径构建。手机调试 APK 位于 `app/build/outputs/apk/debug/app-universal-debug.apk`。正式签名、覆盖安装、发行资产命名和真机验收见 [Android 构建与发行](../docs/ANDROID.md)。调试签名不保证跨构建机器一致。

`app/` 是共享源码的来源；Wear 的 `syncSharedAppSources` 生成共享文件，不手动复制或修改生成目录。开发前阅读 [仓库规则](AGENTS.md) 和 [贡献说明](../CONTRIBUTING.md)。

[测试记录](../docs/TESTING.md) 区分自动检查与未执行的真机验收。Lint 任务成功不表示报告零错误；当前仍是开发预览。

## 来源、文档与许可证

本分支按 [GPL-3.0](LICENSE.md) 发布。上游来源、精确提交和修改范围见 [UPSTREAM.md](../UPSTREAM.md)，上游依赖的声明保留在源码中。

`docs/` 下保留的上游技术文档描述其原有协议和实现；涉及地图、Nostr、更新来源和频道加密时，以仓库根目录的分支文档为准。新增维护记录见 [代码检查](../docs/REVIEW.md)，版本变化见 [CHANGELOG.md](../CHANGELOG.md)。
