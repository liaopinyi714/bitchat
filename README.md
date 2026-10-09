# bitchat

基于官方 Android bitchat v2.0.1 的开源分支。保留原版 Kotlin / Compose 界面与本地 mesh 协议，增加自建频道和 Cloudflare 在线传输。手机应用显示名称仍为 bitchat，安装包标识为 `xyz.liaopinyi714.bitchat`。这是独立分支，不代表原版官方发行。

## 使用和部署

- [Cloudflare 从 GitHub 导入、部署和绑定域名](docs/DEPLOYMENT.md)
- [Android 构建、签名和安装](docs/ANDROID.md)
- [功能范围与验收状态](docs/FEATURES.md)
- [构建与测试结果、尚未完成的验收](docs/TESTING.md)
- [协议和加密边界](docs/PROTOCOL.md)
- [架构与后续扩展](docs/ARCHITECTURE.md)
- [上游来源及许可证](UPSTREAM.md)

默认在线端点：`wss://chat.123456714.xyz`。Cloudflare 由仓库所有者部署；在部署完成前，在线频道不可用，本地蓝牙功能仍可使用。

在频道入口输入名称即可创建或加入，也支持 `/join #channel`。没有地图、地理频道或全站频道目录。知道名称的人可加入公开频道；密码频道的消息由客户端加密。首次在服务器认证成功的身份成为频道创建者，可用 `/pass <password>` 设置密码。密码不持久保存，重启后需重新输入。

## 项目结构

```text
android/             Android 手机端和上游 Wear 模块
worker/              Workers + SQLite Durable Objects 中继
docs/                本分支的部署、协议、架构和验收文档
.github/workflows/   Android 与中继的持续集成
UPSTREAM.md          来源、版本和修改范围
LICENSE.md           GPL-3.0
```

最低 Android 8.0；Wi-Fi Aware 取决于设备支持。Wear 保留上游本地 mesh 功能，在线频道目前仅接入手机端。

本项目使用 Workers Free 与 SQLite Durable Objects，不依赖 R2、D1、KV 或付费推送。免费额度仍有 Cloudflare 平台上限；达到上限时服务可能不可用。容量限制可配置，不能把“免费”理解为无限流量。

当前是开发预览，正式发行前仍需完成真实 Android 设备上的蓝牙、网络切换、后台、媒体和语音验收。源码保留的功能与已经验证的功能在功能文档中分别记录。
