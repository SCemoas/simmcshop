# shopsignscanner（箱子商店告示牌采集）

Minecraft **1.21.11** / Fabric Loader **0.19.5** 客户端模组。
自动捕获玩家周围符合 **QuickShop 箱子商店格式**的告示牌及其坐标、所属**领地**与**最近港口/驿站**，
实时上传到数据库，供商店网站读取。
2.2.3 起默认走 HTTPS 上报（`uploadMode = "api"`），接收端是**本仓库的 [`api-server/`](api-server/README.md)**（独立部署，不依赖商店网站）。

## 功能

- 每 5 秒（可配置）自动扫描周围 32 格（可配置）内已加载区块的告示牌，同时识别正面与背面
- **严格按 4 行商店格式匹配**，且只认橡木告示牌（可关），普通牌子不会误收录
- 识别四种商店状态：出售（sell）、收购（buy）、缺货（sold_out）、空间不足（no_space）
- 状态行与价格行关键词支持多语言：默认覆盖**简体/繁体/英语**，可在配置中扩展其他语言
- 自动剥离告示牌文本中的颜色/格式代码（如 `§a§l` 绿色发光）
- **左上角窗口**：仅在有新数据收录/数据更新时显示，渐入 250ms → 保持 5 秒 → 渐出 750ms 后隐藏；摘要与位置行带**打字机动画**；文字超长自动换行不出框；高度按内容自适应；**宽度可调**（设置界面 200-420px 循环或 `/shopsignscan width <160-500>`）；位置在左上角下移两行给 FPS 留出空间；可在设置里关闭
- **服务器白名单**：默认放行全部服务器（`*`），也可填写自己的服务器主机名，其他服务器零开销跳过；白名单可在配置里增删（精确匹配主机名、去端口、忽略大小写）
- **抓取限速**：自动扫描最快 **10 秒**一次（设置里只有 10/30/60 秒档）；手动按键/命令 **10 秒冷却**
- **上传限速**：默认**每 5 秒最多 10 条**，断线积压补传同样受限，对数据库写入速率恒定
- **物品图标 + 动画**：~~已随 2.1.0 回退移除~~
- **黑名单领地**：设置 → 更多设置 → 黑名单领地管理，名单内的领地商店不采集不入库
- **内存提示**：更多设置页实时显示堆内存占用（>85% 红色提醒）
- **领地 + 港口联动**：每条记录除领地外，还解析**离商店最近的港口/驿站**（地图站 transport_gateways 图层），随记录入库
- **内置设置界面**：窗口开关、自动捕捉开关、捕捉频率、捕捉半径、窗口宽度、领地分表开关、捕捉按键（一键改键）、数据库状态
- **数据库适配**：新记录实时上传（异步、不卡游戏）；**同一坐标同一面的商店数据变化时更新库中原记录而非新增**；连不上时后台指数退避重连（1s → 2s → 4s → … → 60s）并补传（direct 后备模式）
- **API 上报（2.2.3，默认）**：扫描结果改走 HTTPS POST 到 [api-server/](api-server/README.md) 的 ingest 接口，**客户端不再持有数据库口令**；请求带 HMAC 签名与时间戳，服务端按 `location_key` upsert，写库语义与直连完全一致；心跳并进普通批量（一次 POST 同时带 shops 与 heartbeatKeys）。`uploadMode = "direct"` 可切回 MySQL 直连作应急后备
- **上传修复（2.2.2）**：每条记录入队前先落盘 `config/shopsignscanner-pending.jsonl`，队列满只延后不丢弃；游戏重启后自动补传未入库记录。磁盘上的台账会用「追加序号」作身份，压缩重排也不会让未完成的记录被误判为已上传
- 跨会话去重：数据库 `location_key` 唯一键 + 内存去重表，以"服务器+世界+坐标+面"为商店唯一标识；内容未变不重复记录
- **内容判定三项齐比（2.2.2）**：牌面原文、领地、最近港口三项都没变才算「没变」。领地改名后，即使店主没改价，那一行也会重写回库（2.2.0~2.2.1 领地改名后永远停在旧值）
- **分 tick 扫描（2.2.2）**：自动扫描每 tick 只推进 3 个区块，一整轮摊到几十个 tick 上，不再每轮来一次几百毫秒的掉帧尖刺；手动按键仍然一次跑完
- **领地判定按 chunk 分桶（2.2.2）**：查询时只看脚下这一个 chunk 的领地候选，而不是把整个服务器 4000+ 个领地的多边形都比一遍；港口不做索引（整数比较几微秒扫完，且「桶里没有」不等于「附近没有」）
- **商店面板结果缓存 30 秒（2.2.2）**：打开面板不再每次全表查询

## 构建

需要 JDK 21+。构建时把 `JAVA_HOME` 指向本机任意一个 ≥21 的 JDK 即可
（未安装时可借用启动器自带的 JDK：`%APPDATA%\.minecraft\runtime\java-runtime-delta`
或 `java-runtime-beta`）：

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-21
gradlew.bat build
```

产物在 `build/libs/shopsignscanner-2.2.3.jar`（版本号随 `gradle.properties`；MySQL JDBC 驱动已嵌套打包，仅供 direct 后备模式使用）。

## 上报后端（`api-server/`）

mod 在 api 模式下把扫描结果 POST 给 [`api-server/`](api-server/README.md)：一个独立的
Node.js 服务（只依赖 `mysql2`），负责验签、校验、按 `location_key` upsert 进 `shop_signs`。

- **它不是网站的一部分**：独立进程、独立配置、独立数据库账号（只需 `SELECT/INSERT/UPDATE`），
  商店网站挂了也不影响上报
- 接口契约见 [`api-server/CONTRACT.md`](api-server/CONTRACT.md)，改字段前先改契约再改两端代码
- 部署（systemd / nginx / 数据库授权 / 上线自检）见 [`api-server/README.md`](api-server/README.md)
- 自带测试：`cd api-server && npm test`（81 项，不需要数据库）

### 运行自检

五个自检都是纯逻辑、零依赖，可以脱离 Minecraft 单独验证。改动对应模块后请务必跑一遍 ——
它们各自守着一类**静默故障**（不报错，但数据是错的）：

| 自检 | 项数 | 守住的静默故障 |
|---|---|---|
| `tools/DbErrorsSelfTest.java` | 27 | 连接类错误被误丢弃 / 数据错误被无限重试 |
| `tools/OutboxSelfTest.java` | 40 | 补货时同一行被重复交付、compact 后丢行、坏行堵住补货 |
| `tools/LandIndexSelfTest.java` | 13 | chunk 索引与线性扫描结果不一致 → 把错误的领地名写进数据库 |
| `tools/ContentFingerprintSelfTest.java` | 19 | 内容指纹碰撞，真实改动被判为「没变」而漏上传 |
| `tools/ApiModelsSelfTest.java` | 58 | JSON 转义漏字符让整批请求体非法、把 ok=false 当成功、请求体漏 heartbeatKeys、超上限不拦 |

以 `ApiModels`（2.2.3 新增的 API 上报模式）为例，它与它的自检都不依赖 Minecraft 类，也不引 GSON（只依赖 JDK），可以直接编译运行：

```bat
set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAVA=%JAVA_HOME%\bin\java.exe

%JAVAC% -encoding UTF-8 -d build\selftest ^
  src\main\java\com\shopsignscanner\ApiModels.java ^
  src\main\java\com\shopsignscanner\ApiClient.java ^
  tools\ApiModelsSelfTest.java
%JAVA% -Dfile.encoding=UTF-8 -cp build\selftest com.shopsignscanner.ApiModelsSelfTest
```

以 `Outbox` 为例，它与它的自检都不依赖 Minecraft 类，可以直接编译运行：

```bat
set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAVA=%JAVA_HOME%\bin\java.exe

%JAVAC% -encoding UTF-8 -d build\selftest ^
  src\main\java\com\shopsignscanner\Outbox.java tools\OutboxSelfTest.java
%JAVA% -Dfile.encoding=UTF-8 -cp build\selftest com.shopsignscanner.OutboxSelfTest
```

全部通过时退出码为 0。`LandIndexSelfTest` 需要额外把 `gson` 与 `fabric-loader` 的 jar
放进 classpath（`LandResolver` 用它们做 JSON 解析），详见该文件头部注释。

## 安装

1. 客户端安装 Fabric Loader 0.19.5（1.21.11）与 [Fabric API](https://modrinth.com/mod/fabric-api)（0.141.6+1.21.11）
2. 把 `shopsignscanner-2.2.3.jar` 放入 `.minecraft\mods\`（Mods 菜单中显示为「shopsignscanner」）
   ⚠️ 先删掉旧版本的 jar，否则会报 mod 重复
3. **2.2.3 起默认 API 上报**：只需在配置里填 `apiSecret`（与后端 `INGEST_SHARED_SECRET` 一致）。后端不可达时采集的数据先落盘
   `config\shopsignscanner-pending.jsonl`，恢复后自动补传，不会丢
   需要直连 MySQL 时把 `uploadMode` 改成 `"direct"`（应急后备模式）

## 使用

| 操作 | 效果 |
|---|---|
| 走到商店区 | 按设定频率自动扫描；有新数据/数据更新时左上角窗口渐入显示，5 秒后渐出隐藏 |
| 按捕捉键（默认 **K**） | 立即扫描一次 |
| 按查询键（默认 **B**） | **打开商店查询面板**（直接读数据库，需数据库已连接）：搜索物品/店主/领地/港口/服务器，按类型筛选，滚轮滚动，点击查看详情 |
| `/shopsignscan scan` | 立即扫描一次 |
| `/shopsignscan settings` | **打开设置界面**（窗口/频率/半径/按键/数据库状态） |
| `/shopsignscan auto <true\|false>` | 开关自动捕捉 |
| `/shopsignscan radius <4-64>` | 设置捕捉半径（格） |
| `/shopsignscan notify <true\|false>` | 开关左上角窗口 |
| `/shopsignscan status` | 查看配置、累计条数、数据库状态 |

## 设置界面（/shopsignscan settings）

- **左上角窗口**：显示 / 隐藏
- **自动捕捉**：开 / 关
- **捕捉频率**：1 / 2 / 5 / 10 / 30 / 60 秒循环切换
- **捕捉半径**：16 / 24 / 32 / 48 / 64 格循环切换
- **窗口宽度**：200 / 250 / 300 / 360 / 420 px 循环切换（高度按内容自适应）
- **领地分表**：开 / 关（控制是否按领地判定与分表上传）
- **捕捉按键**：点击后按下任意新键即可完成改键（ESC 取消）
- **查询按键**：商店查询面板的开关键位，改绑方式同上
- **数据库**：显示连接状态与已上传条数（连接失败时显示待上传数）

## 商店查询面板（查询键默认 B）

**居中悬浮窗口**（占屏幕约 72%），数据源为 **MySQL 数据库**（异步读取，不卡渲染）：

- **两级视图（同类物品整合）**：整合视图每行 `[n家] 物品名 + 售/收价格区间`（中文序）；
  点击进入该物品的商店列表（出售价低→高、收购价高→低、满仓/缺货垫底），左上「返回」
- **搜索**：物品 / 店主 / 领地 / 港口 / 服务器 / 世界 / 坐标（`-845` 或 `1203,64,-845` 均可）
- **过滤**：默认只显示出售；满仓/缺货默认隐藏可显示；黑名单领地不显示
- **复制**：选中商店后可复制坐标（`x y z` 空格分隔可直接 /tp）、玩家名、港口名
- 数据库未连接时面板提示读取失败；点「刷新」重试
- **2.2.3 起 api 模式下面板直连数据库读取会失败**（这是预期的）：查询面板仍走
  `dbHost`/`dbUser`/`dbPassword` 那条直连通道，站点侧读库不受影响。
  需要面板数据的用户请改用网站商店页面，或临时切 `uploadMode = "direct"`

## 上报失败？（api 模式）

状态行显示 `API 上报失败（…）` 时，按括号里的原因处理：

| 状态行提示 | 原因 | 处置 |
|---|---|---|
| 鉴权失败（HTTP 401/403） | 签名或密钥不匹配 | 检查 `apiSecret` 是否与后端 `INGEST_SHARED_SECRET` 一致 |
| 上报过于频繁（HTTP 429） | 后端限流（600 次/分钟/IP）打满 | 无需处理，稍后自动重试 |
| 后端服务端错误（HTTP 5xx） | 后端侧故障（如数据库不可用） | 等后端恢复；数据会先落盘后补传 |
| 连接后端超时 / 上报请求超时 | 网络不通或后端过慢 | 检查网络与 `apiBaseUrl` 是否可达 |
| 未配置 apiSecret | 配置为空 | 在 `shopsignscanner.json` 填 `apiSecret` |

失败不会丢数据：记录先进 `shopsignscanner-pending.jsonl`，下一轮（最快 5 秒）随新批次重发。

## 连不上数据库？（direct 模式）

左上角窗口的数据库状态行和游戏日志会给出**分类提示 + 本机出口 IP**，按提示处理：

| 状态行提示 | 原因 | 处置 |
|---|---|---|
| IP未放行 | MySQL 账号按 host 白名单限制 | 在数据库侧把状态行显示的「本机IP」加入白名单（见下） |
| 账号或密码错误 | dbUser/dbPassword 不对 | 核对配置文件 |
| host被锁需flush-hosts | 多次连接失败触发 max_connect_errors | 管理员执行 `mysqladmin flush-hosts`（或重启 MySQL） |
| 库名不存在 | dbDatabase 不对 | 核对配置文件 |
| 网络不通/安全组 | 3306 端口不通 | 检查服务器安全组/防火墙放行 3306 |

**放行 IP 的 SQL**（在数据库服务器上用 root 执行；`你的IP` 换成状态行显示的本机 IP）：

```sql
-- MySQL 5.7：新增一条按 IP 的授权（密码自行设置）
GRANT SELECT, INSERT, UPDATE ON shop.* TO 'shop_user'@'你的IP' IDENTIFIED BY '你的密码';
FLUSH PRIVILEGES;
-- 或者放行整段运营商网段（更省心但更宽松）：'你的网段前缀.%'
```

游戏内不用重启：放行后 60 秒内自动重连成功。

## 识别的告示牌格式

第 1 行玩家名、第 2 行状态、第 3 行物品名、第 4 行单价。四种状态示例：

| 状态 | 第 2 行示例 | 记录 type |
|---|---|---|
| 正常出售 | `出售 63` / `販售 63` / `Selling 63` | sell（数量 63） |
| 缺货 | `缺货` / `缺貨` / `Sold out` | sold_out（数量 0） |
| 正常收购 | `收购 1601` / `收購 1601` / `Buying 1601` | buy（数量 1601） |
| 满仓 | `空间不足` / `空間不足` / `No space` | no_space（数量 0） |

第 4 行单价示例：`单价：$1` / `單價：$1` / `Price: $1`。玩家名的发光/颜色代码（§ 开头）会被自动剥离。

**多语言**：关键词前缀匹配、英文忽略大小写。其他语言用户改了插件语言后，把对应的状态词/价格词加进配置即可（见下）。

## 数据存储（MySQL，唯一存储）

**2.2.0 起无本地文件输出**，数据库是唯一存储。默认配置不填写任何数据库地址，需自行在配置文件中填写：

### 表 shop_signs（单表）

首次连接成功后自动建表（2.1+ 自动迁移补列）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| seen_at | DATETIME | 内容最后一次变化的时间 |
| first_seen_at | DATETIME | 首次收录时间（更新不改） |
| last_seen_at | DATETIME | 心跳刷新（每 5 分钟，判断商店是否还在） |
| owner / type / quantity / item / price / price_raw | — | quantity：正常数量 / 0=缺货满仓类 / **-1=官方无限商店** |
| world / x / y / z / side / server / raw | — | 坐标与原文（同位置数据变化时覆盖为最新） |
| land | VARCHAR(64) | 所属领地（Lands），野外为空串 |
| gateway | VARCHAR(192) | 最近港口/驿站 "名称 (距离m)" |
| location_key | CHAR(32) ascii UNIQUE | 位置键 MD5（服务器+世界+坐标+面），每个商店唯一一行 |

- 索引：`idx_owner`、`idx_type_price`、`idx_seen_at`、`idx_last_seen_at`、`idx_item(64)`、`idx_server_world`、`idx_land`
- 领地/港口判定数据来自 Squaremap 地图站 `https://<你的地图站>/tiles/minecraft_overworld/markers.json`（流式解析）；后台每 30 分钟自动刷新
- 上传为**异步**且**限速**（默认每 5 秒最多 10 条）；**同一商店数据变化时更新原记录**（api 模式由后端按 `location_key` upsert，direct 模式为 `ON DUPLICATE KEY UPDATE`）
- 2.2.3 起默认走 API 上报（`uploadMode = "api"`）：一批商店（≤200）连同心跳键 POST 到 `<apiBaseUrl>/api/ingest/shops`，请求带 `timestamp` 与 `X-ShopSign-Signature`（HMAC_SHA256 小写 hex）。后端不可达时数据先落盘，恢复后自动补传
- direct 模式（`uploadMode = "direct"`）下数据库连不上时上传暂停（队列最多 5000 条），恢复后自动补传——超出部分落盘到 `shopsignscanner-pending.jsonl`，不会丢

## 配置文件

`.minecraft\config\shopsignscanner.json`（改完重启游戏生效）：

```json
{
  "radius": 32,
  "intervalTicks": 200,
  "autoScan": true,
  "strictName": false,
  "oakOnly": true,
  "notifyOverlay": true,
  "showWindow": true,
  "dbEnabled": true,
  "dbHost": "",
  "dbPort": 3306,
  "dbDatabase": "shop",
  "dbUser": "",
  "dbPassword": "",
  "uploadMode": "api",
  "apiBaseUrl": "",
  "apiSecret": "",
  "landEnabled": true,
  "mapBaseUrl": "",
  "mapWorld": "minecraft_overworld",
  "landRefreshMinutes": 30,
  "panelWidth": 250,
  "serverAllowlist": ["*"],
  "uploadBatchSize": 10,
  "uploadIntervalMs": 5000,
  "sellKeywords": ["出售", "販售", "贩售", "售卖", "賣出", "卖出", "selling", "sell"],
  "buyKeywords": ["收购", "收購", "购买", "購買", "买入", "買入", "buying", "buy"],
  "soldOutKeywords": ["缺货", "缺貨", "售罄", "已售完", "sold out", "out of stock"],
  "noSpaceKeywords": ["空间不足", "空間不足", "容量不足", "已满", "满仓", "滿倉", "no space", "not enough space", "inventory full"],
  "priceKeywords": ["单价", "單價", "价格", "價格", "price"]
}
```

- `radius`：捕捉半径，4-64 格，默认 32。实际范围还受视距/区块加载限制
- `intervalTicks`：自动捕捉间隔，20 tick = 1 秒，**下限 200（10 秒，保守限速）**，默认 200
- `autoScan`：是否自动捕捉
- `strictName`：true 时第 1 行必须是标准玩家名（字母/数字/下划线，1-16 位）
- `oakOnly`：true 时只扫描橡木告示牌（立式与墙上两种都算）
- `notifyOverlay` / `showWindow`：两个都要为 true 才显示左上角窗口
- `dbEnabled`：direct 模式下是否上传 MySQL（api 模式不看它）；`dbHost` 等五项为 direct 模式的数据库连接信息（默认留空）
- `uploadMode`：**上传模式（2.2.3）**，`"api"`（默认）走 HTTPS POST 到后端 ingest 接口，客户端只持 `apiSecret`；`"direct"` 为 MySQL 直连后备。其他值一律归一为 `"api"` 并在日志里告警
- `apiBaseUrl`：后端根地址（默认空串，需自行填写；由 nginx 把 `/api/ingest/` 反代到 [api-server/](api-server/README.md)），末尾斜杠会被去掉；请求发往 `<apiBaseUrl>/api/ingest/shops`
- `apiSecret`：与后端 `INGEST_SHARED_SECRET` 一致的共享密钥（默认空串）。留空时拒绝上报，`/shopsignscan status` 会提示补配
- `landEnabled`：领地/港口判定开关（关闭后 land 记空串）
- `landBlacklist`：黑名单领地名列表（忽略大小写），名单内的商店不采集不入库；也可在游戏内设置 → 更多设置里管理
- `mapBaseUrl` / `mapWorld` / `landRefreshMinutes`：地图站地址（默认空串，留空则跳过领地/港口判定）、世界名、领地数据刷新分钟数（最小 5）
- `panelWidth`：终端窗口宽度 160-500px，默认 250
- `serverAllowlist`：**服务器白名单**，只有列表内的服务器会被采集（精确匹配主机名，去端口忽略大小写）；加 `*` 放行全部（默认即 `*`）
- `uploadBatchSize` / `uploadIntervalMs`：**上传节流**，每 `uploadIntervalMs` 毫秒最多 `uploadBatchSize` 条（默认 10 条 / 5 秒），补传同限
- `infiniteKeywords`：无限数量关键词（默认 无限/無限/infinite/unlimited）。
  状态行含这些词且带出售/收购词的牌子（如「无限出售」）按**官方无限商店**收录，数量记为 **-1**；
  窗口与查询面板显示「无限」
- 五组关键词：第 2 行/第 4 行行首匹配词，可自行增删以适配其他语言
- **持久化发件箱（2.2.2，无配置项）**：`.minecraft\config\shopsignscanner-pending.jsonl`。
  每条记录入队前先追加到这个文件，入库成功后记账，攒够比例后原子重写压缩。
  队列满时**只落盘不丢弃**，重启后自动补传。不需要也不应该手动编辑或删除它

## 识别规则与局限

- 必须**同时满足**：橡木告示牌（oakOnly 开启时）、第 2 行以状态关键词开头、第 4 行以价格关键词开头且含数字
- 出售/收购行的数量取第 2 行第一个正整数；缺货/空间不足行数量记 0
- 同一商店（同坐标同面）内容变化 → 更新库中原记录；内容未变 → 不重复记录
- 只有已加载区块内的告示牌能被扫描到（大约一个视距内）
- 上传队列上限 5000 条。**2.2.2 起队列满不再丢数据**：超出部分先落盘到
  `shopsignscanner-pending.jsonl`，队列空出后自动补传；游戏重启也会补传
- 超长文本（店主名/物品名超过列宽时）入库前按列宽安全截断，只丢尾部字符，
  不会让整条 INSERT 失败
- 查询面板需要数据库连接才有数据（面板内会提示连接状态）；面板数据缓存 30 秒

## License

基于 Fabric 官方示例模板（CC0）修改，同样以 CC0 提供。
