# shopsignscanner 上报接口契约（ingest API）

mod 与后端服务共享的契约，**任何一侧改动都必须先改这里，再改代码**。
后端实现在本目录（`api-server/`），客户端实现在 mod 的 `src/main/java/com/shopsignscanner/ApiClient.java`。

## 1. 端点

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/ingest/health` | POST | 自检：验签、看服务与数据库状态 |
| `/api/ingest/shops` | POST | 上报一批商店记录 + 心跳键 |
| `/healthz` | GET | 免签名的运维探活（只回服务名与版本，不碰数据库） |

两个 ingest 端点都**必须用 POST**：验签基于原始请求体字节，GET 没有 body。
它们与站点网站完全独立部署，可以挂在同一个域名的 `/api/ingest/` 路径下（nginx 反代到本服务），
这样客户端只需要配一个域名。

## 2. 鉴权

- 头：`X-ShopSign-Signature: <hmac_hex>`
- `hmac = HMAC_SHA256(key = 共享密钥, msg = 原始请求体字节)`，输出小写十六进制
- 请求体 JSON 必须含 `timestamp`（毫秒），与服务端时钟偏差超过 ±5 分钟拒绝（防重放）
- IP 白名单 `INGEST_ALLOWED_IPS`：留空表示不限制（仍受 HMAC 保护）
- 限流：默认 600 次/分钟/IP（批量上报天然比查看页面频繁）

验签失败一律 401；IP 不在白名单 403；超限流 429（带 `Retry-After`）；
服务端没配密钥 500（这是运维问题，不是客户端填错）。

## 3. 请求体（`/api/ingest/shops`）

```json
{
  "timestamp": 1760992000000,
  "client": { "version": "2.2.3", "server": "play.example.com" },
  "shops": [
    {
      "seenAt": "2026-10-20 18:00:00",
      "lastSeenAt": "2026-10-20 18:00:00",
      "owner": "Steve",
      "type": "sell",
      "quantity": 64,
      "item": "minecraft:oak_log",
      "price": 12.5,
      "priceRaw": "12.5",
      "world": "minecraft:overworld",
      "x": 100, "y": 64, "z": -200,
      "side": "front",
      "server": "play.example.com",
      "raw": "§6Steve\n...",
      "land": "",
      "gateway": ""
    }
  ],
  "heartbeatKeys": ["7db68f2a217a7bf33438f71dcb0d10e7"]
}
```

- `shops` 最多 200 条；`heartbeatKeys` 最多 5000 个（与 mod 端 `HEARTBEAT_CHUNK` 一致）
- 单条记录超限只丢这一条（计入 `rejected`），不影响同批其他记录
- 时间格式 `YYYY-MM-DD HH:MM:SS`（**本地时区**，非 UTC/ISO）；服务端原样入库，不做时区换算
- `price` 为数字；非有限值或绝对值 ≥ 1e15 由 mod 端在发送前拦下，服务端再做一次校验
- `quantity = -1` 表示无限
- `type ∈ {sell, buy, sold_out, no_space}`，`side ∈ {front, back}`
- `raw`/`land`/`gateway` 可缺省或 `null`（入库为空串），其余字段必须存在

列宽上限（超出即判该条不合法，与 mod 端 `ColumnLimits` 一致）：
`owner 64`、`item 255`、`priceRaw 255`、`raw 1024`、`land 64`、`gateway 192`、`world 128`、`server 255`。

## 4. 响应

```json
{ "ok": true, "saved": 10, "heartbeated": 42, "rejected": 0, "time": "2026-10-20 18:00:00" }
```

- `saved`：成功 upsert 的商店数
- `heartbeated`：成功刷新 `last_seen_at` 的键数（不存在的键不计）
- `rejected`：因字段不合法被丢弃的条数（**不算服务端错误**，客户端可以丢弃这些记录）
- `ok: false` → 整批失败，客户端应保留 outbox 并退避重试

`/api/ingest/health` 的响应（验签通过后）：

```json
{ "ok": true, "service": "shopsign-ingest-api", "version": "1.0.0",
  "time": "2026-10-20 18:00:00", "database": { "ok": true } }
```

数据库不可用时 `ok` 仍是 `true`、`database.ok` 为 `false` —— 这样客户端能区分
「密钥配错（401）」和「后端数据库挂了（200 + database.ok=false）」。

## 5. 写入语义（与 mod 直连时完全一致）

- 表：`shop_signs`（库 `shop`），写入账号只需 `SELECT, INSERT, UPDATE`
- upsert 列集与 `ON DUPLICATE KEY UPDATE` 列表逐项对齐 `DatabaseReporter.INSERT_SQL`（19 列）
- `first_seen_at` **不进** UPDATE 列表：不存在时填当前时间，已存在则保留原值
  - 实现：`INSERT ... ON DUPLICATE KEY UPDATE ..., first_seen_at = first_seen_at`
    （MySQL 下 `VALUES(x)` 在重复行上会取到新值，因此 `first_seen_at` 用「列自身」保持不动）
  - **这是与 mod 直连路径的唯一行为差异**，且方向正确：mod 直连时若库里已有该行，`first_seen_at` 也是保留的
- `location_key` = `MD5(CONCAT(server,'|',world,'|',x,',',y,',',z,'|',side))` 小写十六进制，
  **由服务端计算**（不信客户端提交的 key），与 `ShopRecord.locationKey()` 逐字节一致
- 心跳 = `UPDATE shop_signs SET last_seen_at = ? WHERE location_key IN (...)`，分 500 一批
- 批量 upsert 每 200 行一条语句
- MySQL 5.7：不用 CTE / 窗口函数；`ON DUPLICATE KEY UPDATE` 里用 `VALUES()` 语法
- 表名只接受 `[A-Za-z0-9_]`（配置里写错直接启动失败，不把用户输入拼进 SQL）

## 6. mod 侧双模式

`uploadMode = api`（默认）| `direct`

- `api`：所有上传走 HTTPS POST，客户端不再持有数据库口令
- `direct`：保留 MySQL 直连全部逻辑（对照测试 / 应急回退）
- 两种模式共用同一套 Outbox / 队列 / 退避 / 心跳收集代码，只有「投递」这一段不同
- `dbPassword` 默认值为空串（api 模式下不使用）

## 7. 变更时必须同步的位置

| 改动 | 需同步 |
|---|---|
| 请求体字段 | mod `ApiReporter` + `api-server/src/validate.js` + 本文件 |
| 写入列集 / UPDATE 列表 | `api-server/src/upsertSql.js` + 本文件 + mod `DatabaseReporter.INSERT_SQL` |
| 位置键算法 | 本文件第 5 节 + `api-server/src/locationKey.js` + mod `ShopRecord.locationKey()`（三处必须一致） |
| 列宽上限 | `api-server/src/validate.js` 的 `LIMITS` + mod `ColumnLimits` |
| 新增端点 | mod `ApiClient` + `api-server/src/http/server.js` + 本文件第 1 节 |
| 状态码语义 | 本文件第 4 节 + mod `ApiClient` 的错误分诊 + `api-server/test/server.test.js` |
