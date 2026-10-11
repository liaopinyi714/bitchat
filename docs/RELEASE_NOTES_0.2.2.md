# bitchat 0.2.2 — 权限页导航栏修复

Android 手机界面修复版本，最低 Android 14（API 34），versionCode 10。沿用现有长期发行证书，可从本分支 0.2.0 / 0.2.1 覆盖升级。

## 修复内容

- 权限说明页的底部按钮栏背景延伸至系统导航区域，消除页面底部颜色不同的横条。
- 系统导航栏保持透明，导航图标随应用深浅主题切换；按钮仍避让系统手势、三键导航和屏幕缺口。
- 权限说明内容按按钮栏的实际高度留出空间，替代固定 88 dp，减少大字体下底部内容被遮挡的问题。

继续保留原有页面文案、字体、配色和两个操作按钮。权限申请范围、频道、消息、加密和中继协议没有变化，也不需要重新部署 Cloudflare。

## 安装与验证

推荐主流手机下载 `bitchat-android-arm64-v8a.apk`，不确定架构时使用 `bitchat-android-universal.apk`。保持原安装并覆盖升级；请勿卸载或清除数据来处理版本问题。

构建、自动检查和签名检查记录见 [验证记录](https://github.com/liaopinyi714/bitchat/blob/v0.2.2/docs/TESTING.md)。实体手机上的权限页面观感尚未由维护环境实测：需检查手势 / 三键导航、深色 / 浅色主题、大字体和横屏，确认底部背景连续、按钮可见可点击、最后一项说明能完整滚动显示。此界面修复不等于已经完成双机无线互通和长期后台验收；具体范围见 [使用前验收](https://github.com/liaopinyi714/bitchat/blob/v0.2.2/docs/OPERATIONS.md)。

本分支基于官方 Android bitchat v2.0.1，按 GPL-3.0 开源。公开频道、密码频道和私聊的安全边界见 [安全说明](https://github.com/liaopinyi714/bitchat/blob/v0.2.2/SECURITY.md)。
