# bitchat 0.2.0 — 发行候选

基于官方 Android bitchat v2.0.1 的独立开源分支，手机最低 Android 14（API 34），应用标识为 `xyz.liaopinyi714.bitchat`。本次为采用长期发行密钥的候选版本，真机持续运行与双机验收仍待完成。

## 本次变化

- 修复蓝牙停止时连接表提前清空、清理协程被取消而未释放 GATT 的问题；客户端、服务端和扫描器在停止时同步清理。
- 跟踪尚在协商的连接，阻止停止 / 重启后的迟到回调恢复旧连接；撤销权限不再中断相关清理流程。
- 修复部分 Compose 文案不随应用语言重新读取的问题，保留原版界面布局、主题和字体。
- 启用独立发行证书，附带从匹配开发签名升级的迁移证明；新增签名、产物与迁移链验证工具。
- 补充安装、数据保留、密钥备份、发布、Cloudflare 维护和使用前验收文档。

保留公开 / 密码频道、Noise 私聊、昵称与历史规则、BLE / Wi-Fi Aware、图片文件、语音及原版其他已接入功能。无地图、地理频道或定位权限。本次不修改加密算法、频道协议或 Worker 存储结构；已正确部署的中继无需数据库迁移。

## 下载和升级

推荐主流手机安装 `bitchat-android-arm64-v8a.apk`；不确定架构时选择 `bitchat-android-universal.apk`。文件大小、源码提交和 SHA-256 见附件 `BUILDINFO.json`、`SHA256SUMS`。

本次 ARM64 包约 **26.17 MB**，通用包约 **58.11 MB**（十进制下载大小，不代表安装后的数据占用）。

Android 14 及以上可使用签名迁移链升级匹配开发证书的旧分支安装。其他电脑自行签名的 debug 包和原版官方包不保证可覆盖。遇到签名冲突请先保留原安装；卸载会删除本地身份和数据。静态签名验证不代表已完成实际手机的数据保留测试。

## 验证范围

完整结果见 [测试记录](https://github.com/liaopinyi714/bitchat/blob/v0.2.0/docs/TESTING.md)。自动回归、构建和 APK 检查不替代 BLE / Wi-Fi Aware、后台、媒体及持续运行的真机验收。项目仍有既有 Lint 问题；没有关闭检查或扩大 baseline 来制造零错误。

手机 648 项测试（3 项既有跳过）、Wear 208 项测试均无失败；中继 22 项测试通过。五个架构产物通过签名与迁移链、资源、权限、反射入口及 64 位原生库 / ZIP 16 KB 对齐检查。手机 Lint 为 453 errors，Wear 为 27 errors，遗留项和限制详见测试记录。

当前为 prerelease，应用内 `releases/latest` 更新入口不会自动接收它。完成 [使用前验收](https://github.com/liaopinyi714/bitchat/blob/v0.2.0/docs/OPERATIONS.md) 后再转为正式 Release；如需代码修复，发布递增版本。

Cloudflare 中继仍由仓库所有者自行部署，根目录为 `worker`。公开频道正文可被中继读取；密码频道和 Noise 私聊的内容保护及限制见 [安全说明](https://github.com/liaopinyi714/bitchat/blob/v0.2.0/SECURITY.md)。
