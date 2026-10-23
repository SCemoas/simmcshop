# shopsignscanner · 上报后端（shopsign-ingest-api）

mod 扫描到的商店牌子，通过 HTTPS POST 汇总到这里，由本服务验签后写进 `shop` 库的 `shop_signs` 表。

**这个服务是给 mod 用的，不是商店网站的一部分**：它独立部署、独立进程、独立数据库账号，
只做「收上报」这一件事。上报链路不依赖商店网站，也不该被它拖慢。

```
Minecraft 客户端 mod (uploadMode=api)
        │  HTTPS POST /api/ingest/shops
        │  X-ShopSign-Signature: HMAC_SHA256(sharedSecret, body)
        ▼
nginx  location /api/ingest/  →  127.0.0.1:3200
        ▼
shopsign-ingest-api（本目录，Node.js，零框架）
        │  INSERT ... ON DUPLICATE KEY UPDATE（每 200 行一条）
        ▼
MySQL 5.7  shop.shop_signs
```

- 接口契约（**改任何字段前先改这里**）：[`CONTRACT.md`](CONTRACT.md)
- 运行时依赖只有 `mysql2`（驱动）；HTTP、HMAC、日志、限流、`.env` 解析全部用 Node 标准库
- 需要 Node.js ≥ 18.17（`fetch`/`node:test` 已内置）

## 目录

| 路径 | 作用 |
|---|---|
| `server.js` | 入口：读配置 → 连库自检 → 监听 → 优雅退出 |
| `src/config.js` | 环境变量 → 配置对象；配错就拒绝启动 |
| `src/http/server.js` | 路由、限流、IP 白名单、读体、验签、响应状态码 |
| `src/auth.js` | HMAC 验签、定长比较、客户端 IP、令牌桶限流 |
| `src/validate.js` | 逐字段校验与列宽上限（坏记录只丢一条） |
| `src/locationKey.js` | 位置键 MD5（与 mod `ShopRecord.locationKey()` 逐字节一致） |
| `src/upsertSql.js` | 19 列 upsert / 心跳 SQL 构造，表名白名单 |
| `src/writers/mysqlWriter.js` | 真正的数据库写入（懒加载 `mysql2`） |
| `src/writers/fakeWriter.js` | 内存写入器：测试与 `DB_DRIVER=fake` 用 |
| `test/` | `node --test`，81 项，不连真库 |
| `deploy/` | systemd 单元、nginx 片段 |
| `scripts/smoke.mjs` | 上线自检（不写业务数据） |

## 本地跑起来（不碰数据库）

```bash
cd api-server
npm install --omit=dev

INGEST_SHARED_SECRET=dev-secret-0123456789 \
DB_USER=dev DB_DRIVER=fake LOG_LEVEL=debug \
node server.js
```

另开一个终端自检（应四项全过）：

```bash
node scripts/smoke.mjs --url http://127.0.0.1:3200 --secret dev-secret-0123456789
```

`DB_DRIVER=fake` 时记录只进内存，**重启即丢**，仅用于联调客户端。

## 部署到 Linux（systemd）

下面按「MySQL 与本服务同机」写；MySQL 在别的机器时把 `DB_HOST` 换成内网地址即可。

1. **建数据库账号**（只给这一张表的读写，不给 DDL）：

   ```sql
   CREATE USER 'shop_ingest'@'127.0.0.1' IDENTIFIED BY '<强口令>';
   GRANT SELECT, INSERT, UPDATE ON shop.shop_signs TO 'shop_ingest'@'127.0.0.1';
   FLUSH PRIVILEGES;
   ```

   表结构由 mod 仓库的 `database/migrate-v2.sql` 负责；本服务**不建表、不迁移**。
   表不存在时写入会报错（日志里是 `ER_NO_SUCH_TABLE`），`/api/ingest/health` 会显示 `database.ok=false`。

2. **放代码与装依赖**：

   ```bash
   sudo useradd --system --home /opt/shopsign-ingest-api --shell /usr/sbin/nologin shopsign
   sudo mkdir -p /opt/shopsign-ingest-api && sudo chown -R shopsign:shopsign /opt/shopsign-ingest-api
   # 把本目录内容放进去（git archive / scp / rsync 都行）
   cd /opt/shopsign-ingest-api
   sudo -u shopsign npm ci --omit=dev
   ```

3. **写配置**（只在这台机器上存在，权限 600）：

   ```bash
   sudo -u shopsign cp .env.example .env
   sudo -u shopsign chmod 600 .env
   # 编辑 .env：HOST=127.0.0.1 PORT=3200 TRUST_PROXY=true
   #            INGEST_SHARED_SECRET=<openssl rand -hex 32>
   #            DB_USER=shop_ingest DB_PASSWORD=<上面那个强口令>
   sudo -u shopsign node server.js --check      # 只校验配置，不监听
   ```

4. **装 systemd 单元并启动**：

   ```bash
   sudo cp deploy/shopsign-ingest-api.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now shopsign-ingest-api
   sudo systemctl status shopsign-ingest-api --no-pager
   sudo journalctl -u shopsign-ingest-api -n 50 --no-pager
   curl -s http://127.0.0.1:3200/healthz      # {"ok":true,...}
   ```

   不想用 systemd 也可以用宝塔的 Node 项目/pm2，但**不要用 root 跑**，并且必须把
   `INGEST_SHARED_SECRET`、`DB_PASSWORD` 放在 `.env` 里而不是命令行参数里。

## nginx 反代

把 `deploy/nginx-ingest.conf` 的内容粘进站点 `server { ... }` 块（宝塔：网站 → 设置 → 配置文件），
`nginx -t && nginx -s reload`。

这样客户端只需要配一个域名：

```json
// .minecraft/config/shopsignscanner.json
{
  "uploadMode": "api",
  "apiBaseUrl": "https://shop.example.com",
  "apiSecret": "<与 INGEST_SHARED_SECRET 完全一致>"
}
```

三个常见坑（片段里已处理，改配置时别改坏）：

- `proxy_pass` **不要**带尾斜杠，否则 `/api/ingest/shops` 会被改写成 `/shops`
- **不要**开启请求体压缩/改写：签名是对原始字节算的
- `client_max_body_size` 要 ≥ `INGEST_MAX_BODY_BYTES`，否则 413 由 nginx 先回，
  客户端拿到的是 HTML 错误页而不是 `{"ok":false}`

## 上线自检与日常运维

```bash
# 通过域名自检（推荐：顺带验证反代与证书）
node scripts/smoke.mjs --url https://shop.example.com --secret '<共享密钥>'

# 探活（免签名，只回服务名与版本）
curl -s https://shop.example.com/healthz

# 看日志（一行一条，带 requestId 便于追踪）
sudo journalctl -u shopsign-ingest-api -f
```

日志里每条写入失败都带 `error` 字段。判断「客户端配错」还是「服务端挂了」看状态码：

| 现象 | 含义 | 处理 |
|---|---|---|
| 401 `签名校验失败` | 客户端 `apiSecret` 与服务端 `INGEST_SHARED_SECRET` 不一致 | 两端对齐密钥后重启客户端 |
| 401 `时间戳超出允许范围` | 客户端机器时钟偏差 > 5 分钟 | 校时（NTP） |
| 403 `不在白名单` | `INGEST_ALLOWED_IPS` 没包含真实客户端 IP（反代没传 XFF） | 检查 nginx `X-Forwarded-For` |
| 429 | 超过 600 次/分钟/IP | 正常情况下只有在异常刷上报时才会出现 |
| 500 `服务端写入失败` | 数据库不可用 / 表不存在 / 权限不足 | 看 journal，`/api/ingest/health` 的 `database` 字段 |
| 200 但 `database.ok=false` | 进程活着但连不上库 | 同上一行：**别误判成密钥错** |

`/api/ingest/health` 在数据库挂掉时仍然返回 200，就是为了让上面这张表里最后两行可区分。

## 开发

```bash
npm test          # 81 项，纯内存，不需要数据库
```

测试覆盖的是「真出事会疼」的几条线：位置键与 Java/MySQL 三处实现的字节一致、
列宽与 ENUM 校验、验签时序与状态码、SQL 列序/占位符数量/`first_seen_at` 不自覆盖、
限流与白名单、以及真实 HTTP 下的写入与心跳。

改动协议相关代码时，按 `CONTRACT.md` 第 7 节的同步表一起改，
并且**先**改契约，再改两端代码。
