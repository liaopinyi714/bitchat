# Cloudflare 部署

## 从 GitHub 导入

1. 登录 Cloudflare，确认 `123456714.xyz` 已在同一账号中生效。
2. 进入 **Workers & Pages → Create application → Import a repository**，授权 GitHub 并选择 `liaopinyi714/bitchat`。选择创建 Worker。
3. Worker 名称填写 **bitchat-relay**，生产分支选择 **main**。
4. **Root directory** 填写 **worker**。配置文件是 `worker/wrangler.jsonc`。
5. 设置构建变量 `PNPM_VERSION=11.25.0`、`WRANGLER_SEND_METRICS=false`。Node 版本由 `worker/.node-version` 固定；若控制台要求单独配置，可填写 `NODE_VERSION=24.19.0`。
6. Build command：`pnpm check && pnpm test`。Cloudflare 自动安装依赖；本仓库已提交锁文件。
7. Deploy command：`pnpm deploy`。保留自动生成的部署 API token，不把 token 写入源码。
8. 点击部署。首次部署会根据迁移配置创建 **RoomObject** 和 **MailboxObject** 两个 SQLite Durable Object 类，绑定名称分别是 **ROOMS** 与 **MAILBOXES**。无需手工创建数据库，也无需开通 R2。
9. 配置已声明 `chat.123456714.xyz` 为 Custom Domain。按控制台要求确认绑定，Cloudflare 会管理 DNS 和 HTTPS。不要自行把这个子域名指向任意服务器 IP。
10. 用浏览器打开 `https://chat.123456714.xyz/health`，应返回 `{"service":"bitchat-relay","protocol":1}`。健康检查只能确认路由；还需安装应用，在两个客户端加入同名频道验证聊天。

控制台字段可能随 Cloudflare 更新。配置依据：[Workers Builds 配置](https://developers.cloudflare.com/workers/ci-cd/builds/configuration/)、[构建工具版本](https://developers.cloudflare.com/workers/ci-cd/builds/build-image/)。Worker 名称必须与配置中的 `name` 一致。

建议只启用 main 分支的生产部署。Durable Object 迁移与自定义域名都由此配置管理；不要将非生产分支指向同一个生产名称和域名。

## 本地部署备用方式

安装 Node.js 24 和 pnpm 11.25.0，在仓库根目录运行：

```sh
cd worker
pnpm install --frozen-lockfile
pnpm check
pnpm test
pnpm exec wrangler login
pnpm deploy
```

检查打包但不部署：`pnpm dry-run`。所有账号认证发生在你自己的终端或浏览器，不需要提供账号密码给其他人。

## 免费计划和容量

保持 Workers Free。SQLite Durable Objects 可用于 Free；本仓库没有需要付费计划的 DO 类型，也没有外部存储依赖。平台额度会变化，以 [Workers 限额](https://developers.cloudflare.com/workers/platform/limits/) 和 [Durable Objects 价格](https://developers.cloudflare.com/durable-objects/platform/pricing/) 为准。

当前默认参数：

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| MAX_CONNECTIONS | 256 | 每个频道 / 邮箱的连接容量保护，可提高 |
| MAX_FRAME_BYTES | 98304 | 单条 JSON 帧的 UTF-8 字节上限 |
| PACKETS_PER_MINUTE | 2400 | 每条连接每分钟收到的帧上限 |
| MAILBOX_MAX_PACKETS | 100 | 每个离线邮箱最多保留的密文包数 |
| MAILBOX_TTL_SECONDS | 300 | 离线密文的最长保留时间 |

这些参数限制单个对象的资源占用，不限制频道总数。增大参数不会增大 Cloudflare 免费额度。

WebSocket 使用 DO Hibernation API。空闲时不运行服务端轮询定时器；客户端在线公告和实际聊天会唤醒对象。私聊跨邮箱转发还需要 DO 内部请求，因此不能只用“WebSocket 消息按 20:1 计费”的比例估计整个服务用量。

频道消息不保存全站档案。服务器只缓存当前在线身份公告和有限、短期的私聊密文；创建者公钥和密码密钥摘要保存在频道元数据中。公开频道内容服务器可读，密码频道和 Noise 私聊的正文由客户端加密，服务器仍可见连接、路由、时间和大小信息。

默认关闭 Workers 应用日志与 observability，不记录消息或密钥。需要诊断时仅记录错误类别、数量和耗时；不要开启包含消息正文的日志。

## 更新与排错

- 推送 main 后，Workers Builds 自动检查和部署。更新前查看迁移变更；不要删除或复用已经应用过的迁移标签。
- 构建找不到 Wrangler：检查根目录是否为 worker，以及 pnpm 安装步骤是否成功。
- 提示 Worker 名称不一致：统一控制台名称和 `wrangler.jsonc` 的 `name`。
- 提示 DO 配置不支持 Free：确认使用 `new_sqlite_classes`，不要改成旧的 `new_classes`。
- 健康检查通过但应用离线：检查 HTTPS 证书、子域名、客户端 RELAY_URL 和当地网络连通性。
- 密码错误：输入正确密码后重新加入。密码修改会断开其他频道客户端，迫使重新输入新密码。
- 出现 429 / rate_limit：降低发送频率或调整容量；先检查账号额度，再提高参数。

大陆网络是否能稳定访问该子域名必须在实际网络验证；域名绑定本身不能保证可达性。高延迟通常可重连恢复，但实时 PTT 对丢包和持续连接更敏感。
