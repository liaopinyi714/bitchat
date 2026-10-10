# 验证记录

当前版本是开发预览。以下结果来自本地构建和合成测试数据，不代表已经完成真实手机、运营商网络或正式 Cloudflare 账号上的验收。

## 已执行

| 检查 | 结果 |
| --- | --- |
| Android 手机通用 debug APK | 构建成功 |
| Wear debug APK | 构建成功 |
| 手机 JVM / Robolectric 测试 | 605 项，0 失败，3 跳过 |
| Wear JVM / Robolectric 测试 | 196 项，0 失败 |
| clientRewriteContractTest | 通过 |
| Worker TypeScript 检查 | 通过 |
| Worker 在 Workerd 中的测试 | 19 项通过 |
| 锁文件依赖安装及 Wrangler dry-run | 通过，未实际部署 |

新增客户端测试覆盖频道名称规范、独立计算的房间 ID / PBKDF2 / 密钥摘要向量、公开封装向量、AES-GCM 篡改和降级拒绝、密码变更，以及 X25519 / HMAC 身份证明。Worker 测试覆盖双密钥身份认证、重放和冒名拒绝、房间与邮箱路由、创建者权限、休眠后状态、密码变更、限流、离线队列确认和过期、同房间定向历史同步。

共享传输测试还验证：镜像传输使用原始取消标识，取消后不会继续发送剩余分片；主传输报告进度，镜像传输保留取消能力。

0.1.1 新增权限回归测试覆盖 Android 8 / 11 / 12 / 13：现代系统只需附近设备权限即可通过 BLE 权限检查；旧系统保留扫描所需的前台定位权限且不请求后台定位；跳过或拒绝附近设备权限仍完成在线启动引导。APK 的合并权限清单已核对 `maxSdkVersion=30`、`neverForLocation` 和后台定位声明的移除。实际页面观感和真实设备上的权限弹窗仍需真机确认。

复查命令：

```sh
cd worker
pnpm install --frozen-lockfile
pnpm check
pnpm test
pnpm dry-run
```

```sh
cd android
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest lintDebug clientRewriteContractTest --no-daemon
```

## Lint 的限制

Lint 已运行，但当前报告仍有错误和警告。上游配置的 `abortOnError=false` 使 Gradle 任务成功结束，这不能解释为 Lint 零错误。报告主要涉及既有翻译、权限、数量资源和 Compose 检查；正式发行前应逐项审查，不能简单关闭检查或把整份报告加入 baseline。

0.1.1 本地报告：手机 320 errors / 362 warnings / 17 hints，Wear 37 errors / 51 warnings / 10 hints；不含被 baseline 过滤的项目。新频道文案支持英文和中文，其他语言使用英文回退，新增 17 条 MissingTranslation 报告保留，未屏蔽检查。新增中继、频道组件和媒体保存 Kotlin 代码未报告 Error；这不替代完整项目审查。

本次修正了旧 Android 上的接收器注册和图片保存，并把文件保存接入系统文件选择器。完整报告仅保留在本地构建目录，不上传设备或开发环境日志。

## 尚未执行

- 实际部署到 `chat.123456714.xyz`，以及大陆移动 / 电信 / 联通与家庭 Wi-Fi 的可达性验证。
- 两台真实 Android 手机上的端到端聊天、BLE / Wi-Fi Aware、网络切换、后台重启、媒体与实时 PTT。
- 物理 Mesh Lab 和仪器测试；因此不能把此预览作为通过生产发布门槛的版本。
- 正式签名、正式发行更新、可重现 APK 双重构建，以及 Cashu 的外部 mint / 资金流程。

部署完成后按 [Android 真机验收](ANDROID.md#必须完成的真机验收) 执行。仅 `/health` 返回正常不能确认聊天和后台功能已经可用。
