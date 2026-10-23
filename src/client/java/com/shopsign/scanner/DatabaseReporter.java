package com.shopsign.scanner;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.shopsign.scanner.ApiReporter;
import com.shopsignscanner.ApiModels;
import com.shopsignscanner.Outbox;

/**
 * 把新收录的商店记录异步上报到 MySQL，并维护「这个商店还在不在」的心跳。
 *
 * <h2>2.2.3：两种上传模式，只有「投递」不同</h2>
 * <ul>
 *   <li><b>api</b>（默认）：HTTPS POST 到商店网站的 ingest 接口。客户端只持有
 *       baseUrl + apiSecret，<b>不再持有数据库口令</b>——口令不再随 mod 分发。</li>
 *   <li><b>direct</b>：本类原有的 MySQL 直连，留作对照测试与应急回退。</li>
 * </ul>
 * 两种模式<b>共用同一套</b> Outbox（磁盘台账）、内存队列、上传节流、指数退避、
 * {@code pendingHeartbeat} 心跳收集与 {@link #statusText()}。差别只有一个私有接口
 * {@link Deliverer}：direct 的实现是 {@link #insertBatchOrFallback}，
 * api 的实现是 {@link ApiReporter#deliver}。
 * <b>不要把上传逻辑复制两份</b>——复制出来的第二份很快就会和第一份在
 * 退避参数、台账确认、心跳时机上漂移，而那种漂移极难归因。
 *
 * <h2>表结构契约（与商店网站共享）</h2>
 * <b>shop_signs 是与商店网站共享的契约表</b> —— 网站只读它。
 * 任何列名/语义变更必须两端一起改，详见 api-server/CONTRACT.md。
 *
 * <h2>表结构 v2 的变化与原因（v2.0.0）</h2>
 * <ul>
 *   <li><b>time → seen_at</b>：<code>time</code> 是 MySQL 的保留字，到处都要反引号；
 *       而且这个名字没表达出真实语义 —— 它只在<em>内容变化</em>时才更新。</li>
 *   <li><b>新增 last_seen_at</b>：这是最重要的修复。原实现里内容未变就不上报，
 *       数据库根本不知道这个商店是否还存在 —— 商店被拆了，网站会永远显示它。
 *       现在扫描到的每个商店都会通过心跳刷新 last_seen_at，网站据此判断有效期。</li>
 *   <li><b>新增 first_seen_at</b>：区分「新收录」与「刚被改动」，INSERT 时写入，之后不再变动。</li>
 *   <li><b>type / side 改为 ENUM</b>：取值是封闭集合（4 种状态、2 个面），
 *       VARCHAR(16) 既浪费空间又没有任何约束。</li>
 *   <li><b>price 改为 DECIMAL(20,4)</b>：价格是货币，DOUBLE 会有浮点误差，
 *       聚合（求和/比较）时更明显。</li>
 *   <li><b>location_key 改为 ascii 字符集</b>：内容是 MD5 十六进制，
 *       原来按 utf8mb4 存占 128 字节，改成 ascii_bin 后 32 字节，唯一索引体积降到 1/4。</li>
 *   <li><b>补齐查询索引</b>：原来除主键与唯一键外<em>没有任何索引</em>，
 *       网站每一次筛选都是全表扫描 + filesort。</li>
 * </ul>
 *
 * <h2>v2.1 的变化（本版本）</h2>
 * <ul>
 *   <li><b>新增 land 列</b>：商店所属领地（Lands），由官方地图站的多边形判定；野外为空串。</li>
 *   <li><b>新增 gateway 列</b>：离商店最近的港口/驿站及距离，来自地图站 transport_gateways 图层。</li>
 *   <li>早期的「按领地分表」方案被并入这两列 + idx_land 索引：
 *       网站契约只认 shop_signs 单表，网站按 land 列筛选即可获得同样的分面能力。</li>
 *   <li>位置键纳入 server（v2.0.0 修复）：<code>MD5(server|world|x,y,z|side)</code>。</li>
 * </ul>
 *
 * <h2>自动迁移</h2>
 * 连接成功后会自动检查 information_schema 并把旧表升级到 v2/v2.1，
 * 不需要用户手工执行 SQL。所有变更都是幂等的（先探测再改）。
 * 等价 SQL 见 database/migrate-v2.sql。
 */
public class DatabaseReporter {
	private static final int QUEUE_CAPACITY = 5000;
	private static final long RETRY_INTERVAL_MS = 60000;
	/** 指数退避的起步间隔。绝大多数连接失败是瞬时的，1 秒足够覆盖 */
	private static final long RETRY_MIN_INTERVAL_MS = 1000L;
	/** 60s / 1s = 6 次翻倍就到封顶，所以 6 级足够 */
	private static final int BACKOFF_MAX_LEVEL = 6;
	/** 连续失败次数（0 表示上次连接成功过），达到 BACKOFF_MAX_LEVEL 后不再增长 */
	private int backoffLevel;
	/**
	 * 查询面板的结果缓存时长。
	 *
	 * <p>30 秒是权衡：玩家打开面板 → 翻看 → 关闭再打开的间隔通常只有几秒到十几秒，
	 * 30 秒能覆盖「反复开关面板比较价格」的完整回合；而超过 30 秒的间隔意味着
	 * 玩家真的去逛了商店回来，此时全表数据大概率已经变化，缓存已过期。
	 *
	 * <p>不要为这个值加配置项：面板读的是全表快照，不是实时数据，
	 * 把它做成可调只会让用户误以为能设成「实时」。
	 */
	private static final long FETCH_CACHE_MS = 30_000L;

	/** 心跳间隔：每这么久把「本轮扫描到的商店」批量刷一次 last_seen_at */
	private static final long HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000;
	/** 单条 UPDATE 里最多放多少个 location_key，避免语句过长 */
	private static final int HEARTBEAT_CHUNK = 500;

	/**
	 * 价格的合理上限。
	 *
	 * 数据库列是 {@code DECIMAL(20,4)}，最多 16 位整数。超过这个量级几乎一定是误解析，
	 * 而写入失败会在 2.2.0 及更早版本里把整条上传管线堵死（见 drainBatch 的说明）。
	 *
	 * @see com.shopsignscanner.ColumnLimits#MAX_PRICE 值的唯一来源（与 ShopSignParser 共用）
	 */
	private static final double MAX_DB_PRICE = com.shopsignscanner.ColumnLimits.MAX_PRICE;

	// -----------------------------------------------------------------
	// 列宽（值的唯一来源见 com.shopsignscanner.ColumnLimits）
	// -----------------------------------------------------------------
	private static final int OWNER_MAX = com.shopsignscanner.ColumnLimits.OWNER_MAX;
	private static final int TYPE_MAX = com.shopsignscanner.ColumnLimits.TYPE_MAX;
	private static final int ITEM_MAX = com.shopsignscanner.ColumnLimits.ITEM_MAX;
	private static final int PRICE_RAW_MAX = com.shopsignscanner.ColumnLimits.PRICE_RAW_MAX;
	private static final int WORLD_MAX = com.shopsignscanner.ColumnLimits.WORLD_MAX;
	private static final int SERVER_MAX = com.shopsignscanner.ColumnLimits.SERVER_MAX;
	private static final int RAW_MAX = com.shopsignscanner.ColumnLimits.RAW_MAX;
	private static final int LAND_MAX = com.shopsignscanner.ColumnLimits.LAND_MAX;
	private static final int GATEWAY_MAX = com.shopsignscanner.ColumnLimits.GATEWAY_MAX;

	private static final String TABLE = "shop_signs";

	// -----------------------------------------------------------------
	// v2.1 表结构（全新安装时使用）
	// -----------------------------------------------------------------
	private static final String CREATE_SQL = """
			CREATE TABLE IF NOT EXISTS shop_signs (
				id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
				location_key  CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MD5(服务器|世界|x,y,z|面)，每个商店唯一一行',
				server        VARCHAR(255) NOT NULL,
				world         VARCHAR(128) NOT NULL,
				x             INT NOT NULL,
				y             INT NOT NULL,
				z             INT NOT NULL,
				side          ENUM('front','back') NOT NULL DEFAULT 'front',
				owner         VARCHAR(64) NOT NULL,
				type          ENUM('sell','buy','sold_out','no_space') NOT NULL,
				quantity      INT NOT NULL DEFAULT 0,
				item          VARCHAR(255) NOT NULL,
				price         DECIMAL(20,4) NOT NULL DEFAULT 0,
				price_raw     VARCHAR(255) NOT NULL,
				raw           VARCHAR(1024) NOT NULL,
				land          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属领地（Lands），野外为空串',
				gateway       VARCHAR(192) NOT NULL DEFAULT '' COMMENT '最近港口/驿站及距离',
				seen_at       DATETIME NOT NULL COMMENT '内容最后一次变化的时间',
				first_seen_at DATETIME NOT NULL COMMENT '首次被收录的时间',
				last_seen_at  DATETIME NOT NULL COMMENT '最后一次被扫到的时间（内容没变也会刷新）',
				PRIMARY KEY (id),
				UNIQUE KEY uq_location_key (location_key),
				KEY idx_owner (owner),
				KEY idx_type_price (type, price),
				KEY idx_seen_at (seen_at),
				KEY idx_last_seen_at (last_seen_at),
				KEY idx_item (item(64)),
				KEY idx_server_world (server(48), world(48)),
				KEY idx_land (land)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""";

	/** 迁移后需要存在的索引：{名称, 定义} */
	private static final String[][] EXPECTED_INDEXES = {
			{"idx_owner", "(`owner`)"},
			{"idx_type_price", "(`type`, `price`)"},
			{"idx_seen_at", "(`seen_at`)"},
			{"idx_last_seen_at", "(`last_seen_at`)"},
			{"idx_item", "(`item`(64))"},
			{"idx_server_world", "(`server`(48), `world`(48))"},
			{"idx_land", "(`land`)"},
	};

	private static final String INSERT_SQL = """
			INSERT INTO shop_signs
			(seen_at, first_seen_at, last_seen_at, owner, type, quantity, item, price, price_raw,
			 world, x, y, z, side, server, raw, land, gateway, location_key)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON DUPLICATE KEY UPDATE
				seen_at      = VALUES(seen_at),
				owner        = VALUES(owner),
				type         = VALUES(type),
				quantity     = VALUES(quantity),
				item         = VALUES(item),
				price        = VALUES(price),
				price_raw    = VALUES(price_raw),
				world        = VALUES(world),
				x            = VALUES(x),
				y            = VALUES(y),
				z            = VALUES(z),
				side         = VALUES(side),
				server       = VALUES(server),
				raw          = VALUES(raw),
				land         = VALUES(land),
				gateway      = VALUES(gateway),
				last_seen_at = VALUES(last_seen_at)
			""";
	// 注意：first_seen_at 刻意不出现在 ON DUPLICATE KEY UPDATE 里 —— 它记录的是「首次收录」，
	// 后续更新必须保留原值。last_seen_at 则每次都被刷新。

	/** INSERT 语句一行的列数，批量版用来算参数偏移 */
	private static final int INSERT_FIELDS = 19;

	/**
	 * 批量版 INSERT（2.2.2）。
	 *
	 * <p>一次 executeBatch 把整批记录变成一条多行 INSERT 发给 MySQL：
	 * 网络往返从 N 次降到 1 次，InnoDB 的 commit 次数也降到 1 次。
	 * 首轮扫描 / 重启补传时这是数量级差异（数千条记录从几十秒降到一秒多）。
	 *
	 * <p><b>为什么必须保留逐行回退</b>：2.2.1 的关键修复是「数据错误只丢那一条，
	 * 不堵死整条管线」。批量执行下 MySQL 报错时无法说出是哪一行有问题
	 * （MySQL 的 SQLException 只带 SQL 片段，不带行号信息；就算手动找
	 * {@code DuplicateKeyException} 之类也没有逐行索引）。
	 * 所以批量失败时回退到逐行执行，让 {@link #insert(ShopRecord)} 里的
	 * 列宽截断和价格上限检查逐条挑出坏数据。
	 */
	private static final String INSERT_BATCH_SQL = """
			INSERT INTO shop_signs
			(seen_at, first_seen_at, last_seen_at, owner, type, quantity, item, price, price_raw,
			 world, x, y, z, side, server, raw, land, gateway, location_key)
			VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
			ON DUPLICATE KEY UPDATE
				seen_at      = VALUES(seen_at),
				owner        = VALUES(owner),
				type         = VALUES(type),
				quantity     = VALUES(quantity),
				item         = VALUES(item),
				price        = VALUES(price),
				price_raw    = VALUES(price_raw),
				world        = VALUES(world),
				x            = VALUES(x),
				y            = VALUES(y),
				z            = VALUES(z),
				side         = VALUES(side),
				server       = VALUES(server),
				raw          = VALUES(raw),
				land         = VALUES(land),
				gateway      = VALUES(gateway),
				last_seen_at = VALUES(last_seen_at)
			""";

	private static final String HEARTBEAT_SQL_PREFIX =
			"UPDATE shop_signs SET last_seen_at = ? WHERE location_key IN (";

	private final ModConfig config;
	private final Consumer<List<String[]>> onEntriesLoaded;
	private final BlockingQueue<PendingUpload> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

	/**
	 * 一条待上传记录，附带它在上传台账里的序号。
	 *
	 * <p>序号是让 {@code drainBatch} 在入库成功后能 {@link Outbox#markDone} 的关键。
	 * {@code seq == 0} 表示这条没有落盘（台账不可用时的退化路径）。
	 */
	private record PendingUpload(ShopRecord record, long outboxSeq) {
	}

	/**
	 * 「把这批记录交出去」——上传模式之间唯一的差异点。
	 *
	 * <p>两个实现：
	 * <ul>
	 *   <li><b>direct</b>：{@link #insertBatchOrFallback(List)}，一条多行 INSERT，
	 *       失败逐条回退（沿用 2.2.2 的全部行为）；</li>
	 *   <li><b>api</b>：{@link ApiReporter#deliver(List, List)}，一次 HTTPS POST，
	 *       顺带把随行心跳一起发出。</li>
	 * </ul>
	 *
	 * <p>返回成功条数（用于 {@link #uploadedCount}）；整体失败必须抛异常，
	 * 由 {@link #drainBatch()} 决定回队重试还是丢弃。心跳键的摘除在
	 * {@link #drainBatch()} 成功分支统一做——投递方不碰 {@link #pendingHeartbeat}。
	 */
	private interface Deliverer {
		/**
		 * @param batch 本批记录（非空）
		 * @return 成功交付的条数
		 * @throws Exception 整体失败（连接/网络/鉴权/服务端）
		 */
		int deliver(List<ShopRecord> batch) throws Exception;
	}

	/**
	 * api 模式的投递实现：把整批 POST 给站点，心跳键随本批一起发出。
	 *
	 * <p>心跳键在 {@link #drainBatch()} 里按 5 分钟间隔取快照后放在
	 * {@link #nextHeartbeatKeys}；本批成功后由 drainBatch 从 pendingHeartbeat
	 * 摘键，失败则快照未动，下一轮随新批次重发。
	 */
	private final class ApiDeliverer implements Deliverer {
		@Override
		public int deliver(List<ShopRecord> batch) throws Exception {
			List<String> keys = nextHeartbeatKeys;
			nextHeartbeatKeys = null;
			ApiModels.IngestResponse response = apiReporter.deliver(batch, keys == null ? List.of() : keys);
			lastHeartbeatAt = System.currentTimeMillis();
			if (response.heartbeated() > 0) {
				System.out.println("[shopsignscanner] 心跳: 随上报刷新 " + response.heartbeated()
						+ " 个商店的存活时间");
			}
			if (response.rejected() > 0) {
				System.err.println("[shopsignscanner] 站点拒绝了 " + response.rejected()
						+ " 条记录（字段不合法，不影响其余记录）");
			}
			// saved 是站点 upsert 的行数，可能小于 batch.size()（部分被 rejected）。
			// 已交付的部分照常确认台账；被拒的条目不重试（重发也会被再拒）。
			return Math.max(0, response.saved());
		}
	}

	/** 当前生效的投递实现（构造时按 uploadMode 选定，之后不变） */
	private final Deliverer deliverer;
	/** api 模式的投递出口；direct 模式下为 null */
	private final ApiReporter apiReporter;
	/** 下一批要随 shops 一起发出的心跳键快照（只在 workerLoop 线程读写） */
	private List<String> nextHeartbeatKeys;

	/**
	 * 上传台账（磁盘兜底）。
	 *
	 * <p>2.2.2 起队列满不再丢记录：记录先追加进 {@link Outbox}，再尝试入内存队列。
	 * 队列满时磁盘上已有一条拷贝，下一轮 {@code refillFromDisk} 会把它捞回来。
	 * 进程崩溃/客户端重启同理。代价是可能重复上传，由 INSERT ... ON DUPLICATE KEY
	 * UPDATE 幂等兜住。
	 *
	 * <p>目录不可写时 {@link Outbox#append} 返回 -1，此时退化为 2.2.1 的纯内存行为
	 * （队列满即丢弃），并在 {@link #statusText()} 里显示出来。
	 */
	private Outbox outbox;
	/** 自上次心跳以来扫描到的所有位置键（去重） */
	private final Set<String> pendingHeartbeat = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "shopsignscanner-DB");
		t.setDaemon(true);
		return t;
	});

	private volatile Connection connection;
	private volatile String status = "未连接";
	private volatile String lastError = "";
	private volatile int uploadedCount;
	private volatile int queuedCount;
	private volatile int heartbeatCount;
	/** 因数据错误被丢弃的条数（重试也不会成功，见 drainBatch） */
	private volatile int discardedCount;
	/**
	 * 因队列满且落盘不可用而丢失的条数。
	 *
	 * <p>2.2.2 起正常情况下这个数字恒为 0：队列满只意味着记录转去上传台账待传，
	 * 由 {@link #refilledCount} 统计。只有台账本身坏掉（目录不可写等）才会真的丢。
	 */
	private volatile int droppedCount;
	/** 已从上传台账补回内存队列的条数（进程重启后的补传也计入） */
	private volatile int refilledCount;
	/** 当前堆在磁盘台账里还没确认入库的条数（诊断用） */
	private volatile int pendingDiskCount;
	private volatile long lastHeartbeatAt;
	private boolean entriesDelivered;
	private boolean migrated;

	/** fetchShopsAsync 的结果缓存（见 FETCH_CACHE_MS）。只在查询线程读写。 */
	private volatile List<ShopRecord> shopsCache;
	private volatile long shopsCacheAt;
	/** 是否已有一次全表查询在飞行中。同时开两条连接只会互相拖慢。 */
	private boolean fetchInFlight;
	/** 飞行期间到来的调用方，查询结束后一次性全部交付。 */
	private final List<java.util.function.BiConsumer<List<ShopRecord>, String>> pendingFetchCallbacks =
			new ArrayList<>();

	public DatabaseReporter(ModConfig config, Consumer<List<String[]>> onEntriesLoaded) {
		this.config = config;
		this.onEntriesLoaded = onEntriesLoaded;
		if (config != null && config.isApiMode()) {
			this.apiReporter = new ApiReporter(config.apiBaseUrl, config.apiSecret,
					ShopSignScannerClient.MOD_VERSION, serverNameForApi(), null);
			this.deliverer = new ApiDeliverer();
		} else {
			this.apiReporter = null;
			this.deliverer = this::insertBatchOrFallback;
		}
	}

	/**
	 * api 模式请求体里的 server 字段。
	 *
	 * <p>与 {@code ShopSignScannerClient.currentServerName()} 同一套取值
	 * （单机 "singleplayer"、多人用 info.ip），但这里不依赖那个类的私有方法：
	 * reporter 可能在命令上下文里被构造，直接读一次当前连接状态更稳。
	 * 取不到时给 "unknown"，站点照常接收（server 只用于展示与 location_key）。
	 */
	private static String serverNameForApi() {
		try {
			net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
			if (minecraft.getSingleplayerServer() != null) return "singleplayer";
			var info = minecraft.getCurrentServer();
			if (info != null && info.ip != null && !info.ip.isBlank()) return info.ip;
		} catch (Throwable ignored) {
			// 没在游戏环境里（自检/单文件运行）：退回默认值，不影响构造
		}
		return "unknown";
	}

	/**
	 * 上传台账实例。
	 *
	 * <p>配置目录取 {@code FabricLoader.getInstance().getConfigDir()}：
	 * 这是 mod 自己的位置（{@code .minecraft/config/shopsignscanner-pending.jsonl}），
	 * 与旧版 txt 兜底放在同一处，玩家知道去哪儿找。
	 *
	 * <p>抽成方法是为了可测试：在加载不了 Fabric 的环境里可以替换。
	 */
	private Outbox newOutbox() {
		return new Outbox(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
	}

	public void start() {
		executor.execute(this::workerLoop);
	}

	/**
	 * 新记录入队上传。
	 *
	 * <p><b>2.2.2 起不再丢记录</b>：先追加进上传台账（磁盘），再尝试入内存队列。
	 * 落盘优先于入队是关键顺序——先保证「记录已经在不会丢的地方」，
	 * 再去赌队列有没有位置：
	 * <ul>
	 *   <li>入队成功 → 正常上传，成功后 {@link Outbox#markDone} 确认；</li>
	 *   <li>入队失败（队列满）→ 记录已在磁盘上，下一轮 {@code refillFromDisk} 捞回；</li>
	 *   <li>进程崩溃 / 客户端重启 → 磁盘上未确认的记录同样由 refill 捞回。</li>
	 * </ul>
	 *
	 * <p>落盘本身不可用（权限、磁盘满）时 {@link Outbox#append} 返回 -1，
	 * 此时退化为 2.2.1 的行为：队列满就真的丢了，日志如实说明。
	 */
	public void submit(ShopRecord record) {
		if (!uploadingEnabled()) return;
		// 台账是懒创建的：两种模式都需要（队列满的兜底）
		if (outbox == null) outbox = newOutbox();
		long seq = outbox.append(Outbox.PendingRow.of(record.toOutboxFields()));
		if (seq < 0) {
			// 落盘不可用（返回 -1）：纯内存路径，与 2.2.1 一致，队列满了就真丢
			if (!queue.offer(new PendingUpload(record, 0))) {
				droppedCount++;
				System.err.println("[shopsignscanner] 上传队列已满（上限 " + QUEUE_CAPACITY
						+ " 条），且上传台账不可用，本条记录已丢失");
			} else {
				queuedCount++;
			}
			return;
		}
		if (!queue.offer(new PendingUpload(record, seq))) {
			// 已在磁盘上，下一轮 refillFromDisk 会补货回队列。
			// 注意这里不递增 droppedCount：它不是丢失，只是延后。
			pendingDiskCount++;
			if (pendingDiskCount <= 1 || pendingDiskCount % 1000 == 0) {
				System.err.println("[shopsignscanner] 上传队列已满（上限 " + QUEUE_CAPACITY
						+ " 条），本条已转入上传台账待传（台账共 " + outbox.pendingCount() + " 条）");
			}
			return;
		}
		queuedCount++;
	}

	/**
	 * 记录「本轮扫描时这个商店还在」。
	 *
	 * 由扫描器在每次扫描后调用，传入本次看到的所有商店位置键。
	 * 累积到心跳间隔后批量刷新 last_seen_at —— 这正是 v1 缺失的能力：
	 * 内容没变的商店也要让数据库知道它还活着，否则商店被拆掉后网站会一直显示它。
	 */
	public void markSeen(Set<String> locationKeys) {
		if (!uploadingEnabled() || locationKeys == null || locationKeys.isEmpty()) return;
		pendingHeartbeat.addAll(locationKeys);
	}

	/**
	 * 当前配置是否允许上传。
	 *
	 * <p>api 模式<b>不看 dbEnabled</b>：上传走 HTTPS，跟数据库开没开没关系；
	 * direct 模式仍看 dbEnabled（关掉数据库就是关掉上报）。
	 */
	private boolean uploadingEnabled() {
		return config == null || config.isApiMode() || config.dbEnabled;
	}

	public String statusText() {
		// api 模式：展示的是「往站点投递」的状态，与 MySQL 连没连无关，
		// 所以不该复用 direct 的已连接/未连接措辞。
		if (config != null && config.isApiMode()) {
			return apiStatusText();
		}
		if (!config.dbEnabled) return "数据库: 已禁用";

		// 两个计数都必须显式展示：
		// droppedCount 是真丢（只有台账本身坏掉时才会涨），读者需要立刻知道；
		// pendingDiskCount 是队列满时转入磁盘的状态，说明补传正在进行中。
		String lost = "";
		if (discardedCount > 0 || droppedCount > 0) {
			List<String> parts = new ArrayList<>();
			if (discardedCount > 0) parts.add("数据错误丢弃 " + discardedCount);
			if (droppedCount > 0) parts.add("丢失 " + droppedCount);
			lost = " · " + String.join(" · ", parts);
		}
		String disk = pendingDiskCount > 0 ? " · 磁盘待传 " + pendingDiskCount : "";

		if ("已连接".equals(status)) {
			return "数据库: 已连接 · 已上传 " + uploadedCount + " 条 · 心跳 " + heartbeatCount
					+ " 次" + lost + disk;
		}
		String base;
		if (queuedCount > 0) base = "未连接 · 待上传 " + queuedCount + " 条";
		else base = "未连接";
		// 失败自诊断：错误分类 + 本机出口 IP（仅连接/权限类错误才提示放行 IP）
		String hint = hintFor(lastError);
		if (!hint.isEmpty()) base += "（" + hint + "）";
		String ip = publicIp;
		if (isConnectivityHint(hint) && !ip.isEmpty()) base += " · 本机IP:" + ip;
		return "数据库: " + base + lost + disk;
	}

	/**
	 * api 模式的状态行。
	 *
	 * <p>direct 模式的措辞（已连接/未连接）在这里是误导——api 模式根本没有连接池，
	 * 每批都是一次独立的 HTTPS 请求。所以只描述「站点最近一次投递的结果」：
	 * <ul>
	 *   <li>没有错误 → 「API 上报 · 已上传 N 条」；</li>
	 *   <li>待上传/磁盘待传照常显示（队列与台账是两种模式共用的）；</li>
	 *   <li>失败时附 apiSecret 排查提示：这是 api 模式最常见的配置错误，
	 *       而且线上几乎没有别的线索可查。</li>
	 * </ul>
	 */
	private String apiStatusText() {
		String lost = "";
		if (discardedCount > 0 || droppedCount > 0) {
			List<String> parts = new ArrayList<>();
			if (discardedCount > 0) parts.add("数据错误丢弃 " + discardedCount);
			if (droppedCount > 0) parts.add("丢失 " + droppedCount);
			lost = " · " + String.join(" · ", parts);
		}
		String disk = pendingDiskCount > 0 ? " · 磁盘待传 " + pendingDiskCount : "";

		String base;
		if (lastError != null && !lastError.isBlank()) {
			base = "API 上报失败";
			String hint = hintFor(lastError);
			if (!hint.isEmpty()) base += "（" + hint + "）";
			base += " · 检查 apiSecret 是否与站点 INGEST_SHARED_SECRET 一致";
		} else if (queuedCount > 0) {
			base = "API 上报 · 待上传 " + queuedCount + " 条";
		} else {
			base = "API 上报 · 已上传 " + uploadedCount + " 条";
		}
		if (heartbeatCount > 0) base += " · 心跳 " + heartbeatCount + " 次";
		return base + lost + disk;
	}

	/** 把 JDBC 异常翻译成玩家能理解的处置提示；实现见 {@link DbErrors#hintFor} */
	public static String hintFor(String err) {
		return DbErrors.hintFor(err);
	}

	/** 该提示是否属于「连接/权限」类；实现见 {@link DbErrors#isConnectivityHint} */
	static boolean isConnectivityHint(String hint) {
		return DbErrors.isConnectivityHint(hint);
	}

	/** 本机公网出口 IP（失败时获取一次，10 分钟内不重复请求） */
	private volatile String publicIp = "";
	private long publicIpFetchedAt;
	private final java.net.http.HttpClient ipClient = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(5))
			.build();

	private void fetchPublicIpIfNeeded() {
		long now = System.currentTimeMillis();
		if (!publicIp.isEmpty() && now - publicIpFetchedAt < 600_000L) return;
		publicIpFetchedAt = now;
		for (String endpoint : new String[]{"https://ifconfig.me/ip", "https://api.ipify.org"}) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
						.timeout(java.time.Duration.ofSeconds(5))
						.header("User-Agent", "shopsignscanner/1.0")
						.GET().build();
				HttpResponse<String> response = ipClient.send(request, HttpResponse.BodyHandlers.ofString());
				String ip = response.body() == null ? "" : response.body().trim();
				if (response.statusCode() == 200 && ip.matches("[0-9a-fA-F.:]{3,45}")) {
					publicIp = ip;
					return;
				}
			} catch (Exception ignored) {
				// 获取失败不影响主流程，status 里就不显示 IP
			}
		}
	}

	public String lastError() {
		return lastError;
	}

	// -----------------------------------------------------------------
	// 主循环
	// -----------------------------------------------------------------

	private void workerLoop() {
		// api 模式下不看 dbEnabled：上传走 HTTPS，数据库开没开都不影响。
		// direct 模式关掉数据库 = 关掉上报，直接停掉工作线程（2.2.2 行为）。
		if (!uploadingEnabled()) {
			status = "已禁用";
			return;
		}
		outbox = newOutbox();
		// 上传节流：每批最多 uploadBatchSize 条，批间固定等待 uploadIntervalMs。
		// 无论队列积压多少（断线补传也一样），对下游的写入速率恒定。
		long batchDelay = Math.max(1000, config.uploadIntervalMs);
		while (true) {
			try {
				// api 模式没有「连接」可建，这句在 direct 模式下建连 + 迁移。
				ensureConnected();
				refillFromDisk();
				drainBatch();
				maybeFlushHeartbeat();
				Thread.sleep(batchDelay);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				// 数据错误不该走到这里（drainBatch 已就地丢弃）。兜底：绝不当作连接故障、
				// 不退避 60 秒 —— 否则又会变成「一条坏数据拖垮整条管线」。
				if (isDataError(e)) {
					discardedCount++;
					lastError = shortMessage(e);
					System.err.println("[shopsignscanner] 数据错误（已忽略，不重试）: " + lastError);
					continue;
				}
				status = "连接失败";
				lastError = shortMessage(e);
				// api 模式的失败重试提示：先清一次旧错误，让 statusText 回到「API 上报失败」。
				System.err.println("[shopsignscanner] 上报失败（" + (delayLabel()) + "后重试）: " + e);
				if (config != null && config.isApiMode()) {
					System.err.println("[shopsignscanner] 排查：检查 apiSecret 是否与站点 INGEST_SHARED_SECRET 一致"
							+ "，以及 apiBaseUrl 是否可达（" + config.apiBaseUrl + "）");
				} else {
					fetchPublicIpIfNeeded();
					String hint = hintFor(lastError);
					// 只有「连接/权限」类错误才提示去放行 IP。
					// 否则会把玩家引向排查白名单，而真正的问题根本不在网络。
					boolean ipHintUseful = isConnectivityHint(hint) && !publicIp.isEmpty();
					System.err.println("[shopsignscanner] 数据库连接失败（" + hint + "）"
							+ (ipHintUseful ? "  ｜ 本机出口 IP: " + publicIp + "（需加入数据库白名单）" : ""));
				}
				long delay = nextBackoffDelay();
				closeConnection();
				try {
					Thread.sleep(delay);
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
	}

	/** 日志里描述当前退避时长（供 api 模式复用 direct 的退避提示） */
	private String delayLabel() {
		return (nextBackoffDelay() / 1000) + " 秒";
	}

	/**
	 * 重连退避：指数增长 1s → 2s → 4s → …，封顶 {@link #RETRY_INTERVAL_MS}（60s），
	 * 再叠 ±20% 抖动。
	 *
	 * <p>为什么要抖：一次 MySQL 维护 / 机房抖动会让几十台客户端同时失败，
	 * 固定 60 秒意味着它们会在同一秒一起重试 ——
	 * 抖动把它们的重试时刻打散到 48–72 秒之间的任意时刻。
	 * 数据库恢复时面对的是平滑的流量而不是一个尖峰。
	 *
	 * <p>2.2.1 及更早只有固定 60 秒：第一次失败就要等一分钟才试第二次，
	 * 而绝大多数连接失败是瞬时的（MySQL 的 wait_timeout 掐掉空闲连接、
	 * 一次网络抖动、Wi-Fi 换 AP）。从 1 秒起步让瞬时故障几乎无感，
	 * 只有持续故障才会逐步等久。
	 */
	private long nextBackoffDelay() {
		long base = RETRY_INTERVAL_MS;
		for (int i = 0; i < backoffLevel && base > RETRY_MIN_INTERVAL_MS; i++) {
			base /= 2;
		}
		backoffLevel = Math.min(backoffLevel + 1, BACKOFF_MAX_LEVEL);
		double factor = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
		long delay = (long) (base * factor);
		return Math.max(250L, Math.min(RETRY_INTERVAL_MS, delay));
	}

	/** 连接成功后复位退避，让下一次瞬时故障又从 1 秒起步 */
	private void resetBackoff() {
		backoffLevel = 0;
	}

	/**
	 * 把上传台账里未确认的记录补回内存队列。
	 *
	 * <p>每轮执行，但 {@link Outbox#refillFromDisk} 在无事可做时几乎零成本：
	 * 正常情况下记录秒入库、markDone 秒被调用，磁盘标志很快变 false，
	 * 一次文件都不读。真正干活的是两种场景：
	 * <ul>
	 *   <li>队列满：submit 里 offer 失败的那些记录此刻捞回来重试；</li>
	 *   <li>重启：上一次进程留下的未确认记录。</li>
	 * </ul>
	 *
	 * <p>补回量受队列剩余容量限制（这里按 uploadBatchSize 补），
	 * 避免一次把 5000 条全塞进队列把真正的在途记录挤掉顺序。
	 */
	private void refillFromDisk() {
		if (outbox == null) return;
		int max = Math.max(1, config.uploadBatchSize);
		long n = outbox.refillFromDisk(row -> {
			ShopRecord record = recordOf(row);
			if (record == null) return Boolean.TRUE; // 坏行已告警，跳过但不堵后续行
			return queue.offer(new PendingUpload(record, row.seq()));
		}, max);
		if (n > 0) {
			refilledCount += (int) n;
		}
		pendingDiskCount = outbox.pendingCount();
		outbox.maybeCompact();
	}

	/** 台账行还原成 ShopRecord；数字字段损坏时返回 null（调用方跳过该行） */
	private static ShopRecord recordOf(Outbox.PendingRow row) {
		return ShopRecord.fromOutboxFields(row);
	}

	/**
	 * 上传一批（最多 uploadBatchSize 条）。
	 *
	 * <p><b>失败必须分两类处理</b>（2.2.1 的关键修复）：
	 * <ul>
	 *   <li><b>数据错误</b>（如价格超出 DECIMAL 范围、数据过长）：
	 *       这条记录重试多少次都不会成功，必须<em>丢弃</em>。
	 *       2.2.0 及更早版本把它重新入队 + 关连接 + 60 秒退避，
	 *       导致一条坏数据永久堵死整条上传管线 ——
	 *       玩家看到的现象就是「mod 老是连不上数据库」，
	 *       而真实原因与网络/白名单毫无关系。</li>
	 *   <li><b>连接类错误</b>：回队重试并抛出，由主循环重连。</li>
	 * </ul>
	 */
	private void drainBatch() throws Exception {
		int max = Math.max(1, config.uploadBatchSize);
		List<PendingUpload> batch = new ArrayList<>(Math.min(max, 64));
		for (int i = 0; i < max; i++) {
			PendingUpload item = queue.poll();
			if (item == null) break;
			batch.add(item);
		}
		if (batch.isEmpty()) return;

		// 一次性取出再批量提交：比「一条一条 poll + insert」少 N-1 次数据库往返。
		// 失败路径上 insertBatchOrFallback 会逐条重跑，保留 2.2.1 的
		// 「数据错误只丢那一条、不堵死管线」行为。
		try {
			List<ShopRecord> records = new ArrayList<>(batch.size());
			for (PendingUpload item : batch) records.add(item.record());
			// 心跳键随本批一起发出（api 模式）；direct 模式下 nextHeartbeatKeys
			// 始终为 null，方法引用走的 insertBatchOrFallback 忽略它。
			nextHeartbeatKeys = config.isApiMode() ? pollHeartbeatKeysForBatch() : null;
			int done = deliverer.deliver(records);
			// 投递成功 → 这批带走的心跳键从 pendingHeartbeat 摘除。
			// 失败则快照未被移除，下一轮随新批次重发（重复心跳是幂等的）。
			if (nextHeartbeatKeys != null && !nextHeartbeatKeys.isEmpty()) {
				heartbeatCount += nextHeartbeatKeys.size();
			}
			nextHeartbeatKeys = null;
			uploadedCount += done;
			queuedCount -= batch.size();
			// 台账行统一在这里确认：整批成功、批量回退后部分丢弃、以及被
			// bind 阶段价格检查拦下的情况，台账都不能再留着（否则 compact
			// 永远判定有未完成，反复重写文件）。
			for (PendingUpload item : batch) confirmOutbox(item);
		} catch (Exception e) {
			// 投递失败：随批发出的心跳键保留在 pendingHeartbeat 里（快照未 removeAll），
			// 下一轮随新批次重发。心跳重发是幂等的（touchLastSeen），无需去重。
			nextHeartbeatKeys = null;
			if (isDataError(e)) {
				// 丢弃整批里的一条都不可能成功，说明这批都坏在同一处
				// （例如价格上限检查在 bind 阶段就抛）。逐条确认台账后继续。
				queuedCount -= batch.size();
				discardedCount += batch.size();
				for (PendingUpload item : batch) {
					confirmOutbox(item);
					System.err.println("[shopsignscanner] 丢弃无法上报的记录（不再重试）: "
							+ describeRecord(item.record()) + " ｜ " + shortMessage(e));
				}
				return;
			}
			System.err.println("[shopsignscanner] 上报失败（下一轮重试）: " + e);
			// 回队：顺序无所谓（幂等），但要把台账行留着等下一轮。
			for (PendingUpload item : batch) queue.offer(item);
			closeConnection();
			throw e;
		}
	}

	/**
	 * 入库成功（或确认要丢弃）后把台账里的对应行标记为已完成。
	 *
	 * <p>{@code outboxSeq == 0} 表示这条本来就没落盘（台账不可用时的退化路径），
	 * 什么都不用做。
	 */
	private void confirmOutbox(PendingUpload item) {
		if (item.outboxSeq() > 0 && outbox != null) outbox.markDone(item.outboxSeq());
	}

	/**
	 * 是否「数据被数据库拒绝」类错误 —— 实现见 {@link DbErrors#isDataError}。
	 * 抽到纯逻辑类是为了可测试（本类依赖 Minecraft/Fabric，测试 JVM 加载不了）。
	 */
	static boolean isDataError(Throwable e) {
		return DbErrors.isDataError(e);
	}

	/** 记录的可读描述，用于日志定位是哪块牌子出的问题 */
	private static String describeRecord(ShopRecord r) {
		return r.type() + " " + r.item() + " @ " + r.price() + "（" + r.owner() + "）"
				+ " " + r.world() + " " + r.x() + "," + r.y() + "," + r.z()
				+ " ｜ 价格原文: " + r.priceRaw();
	}

	// -----------------------------------------------------------------
	// 连接与迁移
	// -----------------------------------------------------------------

	/** api 模式下 ensureConnected 空转：没有连接池，每批都是独立的 HTTPS 请求 */
	private void ensureConnected() throws Exception {
		if (config != null && config.isApiMode()) {
			if (lastError != null && !lastError.isBlank()) lastError = "";
			return;
		}
		if (connection != null && !connection.isClosed()) return;
		status = "连接中...";
		connection = DriverManager.getConnection(jdbcUrl(), config.dbUser, config.dbPassword);
		try (Statement st = connection.createStatement()) {
			st.executeUpdate(CREATE_SQL);
		}
		// 已有旧表的安装：自动升级到 v2/v2.1（幂等）
		if (!migrated) {
			migrate();
			migrated = true;
		}
		loadExistingEntries();
		status = "已连接";
		lastError = "";
		// 连上了就把退避等级清零：下一次瞬时故障再从 1 秒起步，
		// 而不是接着上次失败的等级继续等。
		resetBackoff();
	}

	/**
	 * 把 v1 表升级到 v2，再补齐 v2.1 的 land/gateway 列。
	 *
	 * 全部步骤都是「先查 information_schema，确认缺失才改」，因此可以重复执行。
	 * 任何一步失败都会抛出，由主循环捕获后重连重试（不会静默跳过）。
	 */
	private void migrate() throws Exception {
		Set<String> columns = queryColumnNames();
		Map<String, String> types = queryColumnTypes();

		// 1) time -> seen_at（保留数据）
		if (!columns.contains("seen_at")) {
			if (columns.contains("time")) {
				exec("ALTER TABLE shop_signs CHANGE COLUMN `time` `seen_at` DATETIME NOT NULL");
				System.out.println("[shopsignscanner] 迁移: time → seen_at");
			} else {
				exec("ALTER TABLE shop_signs ADD COLUMN `seen_at` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'");
				exec("UPDATE shop_signs SET seen_at = NOW() WHERE seen_at = '1970-01-01 00:00:00'");
				System.out.println("[shopsignscanner] 迁移: 新增 seen_at");
			}
			columns.add("seen_at");
		}

		// 2) first_seen_at
		if (!columns.contains("first_seen_at")) {
			exec("ALTER TABLE shop_signs ADD COLUMN `first_seen_at` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'");
			exec("UPDATE shop_signs SET first_seen_at = seen_at WHERE first_seen_at = '1970-01-01 00:00:00'");
			System.out.println("[shopsignscanner] 迁移: 新增 first_seen_at");
		}

		// 3) last_seen_at —— 老数据没有心跳记录，只能先用 seen_at 兜底
		if (!columns.contains("last_seen_at")) {
			exec("ALTER TABLE shop_signs ADD COLUMN `last_seen_at` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'");
			exec("UPDATE shop_signs SET last_seen_at = seen_at WHERE last_seen_at = '1970-01-01 00:00:00'");
			System.out.println("[shopsignscanner] 迁移: 新增 last_seen_at");
		}

		// 4) type / side 收敛为 ENUM —— 先把越界值修正，否则 MODIFY 会失败
		if (isVarchar(types.get("type"))) {
			exec("UPDATE shop_signs SET type = 'sell' WHERE type NOT IN ('sell','buy','sold_out','no_space')");
			exec("ALTER TABLE shop_signs MODIFY COLUMN `type` ENUM('sell','buy','sold_out','no_space') NOT NULL");
			System.out.println("[shopsignscanner] 迁移: type → ENUM");
		}
		if (isVarchar(types.get("side"))) {
			exec("UPDATE shop_signs SET side = 'front' WHERE side NOT IN ('front','back')");
			exec("ALTER TABLE shop_signs MODIFY COLUMN `side` ENUM('front','back') NOT NULL DEFAULT 'front'");
			System.out.println("[shopsignscanner] 迁移: side → ENUM");
		}

		// 5) price: DOUBLE -> DECIMAL（货币不该用浮点）
		String priceType = types.getOrDefault("price", "");
		if (priceType.contains("double") || priceType.contains("float")) {
			exec("ALTER TABLE shop_signs MODIFY COLUMN `price` DECIMAL(20,4) NOT NULL DEFAULT 0");
			System.out.println("[shopsignscanner] 迁移: price → DECIMAL(20,4)");
		}

		// 6) location_key: utf8mb4 -> ascii（内容是 MD5 十六进制，占用可降到 1/4）
		Set<String> keyCharset = queryColumnCharset("location_key");
		if (!keyCharset.isEmpty() && !keyCharset.contains("ascii")) {
			exec("ALTER TABLE shop_signs MODIFY COLUMN `location_key` "
					+ "CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
			System.out.println("[shopsignscanner] 迁移: location_key → ascii 字符集");
		}

		// 7) 重算位置键：v1 的键不含 server，跨服务器同坐标会互相覆盖。
		//    用与 Java 端完全相同的拼接方式重算，幂等（重算后差异数为 0）。
		//    注意 MySQL 的 MD5() 也是小写十六进制，与 Java 端一致。
		String newKeyExpr = "MD5(CONCAT(server,'|',world,'|',x,',',y,',',z,'|',side))";
		int staleKeys = queryInt("SELECT COUNT(*) FROM shop_signs WHERE location_key <> " + newKeyExpr);
		if (staleKeys > 0) {
			exec("UPDATE shop_signs SET location_key = " + newKeyExpr);
			System.out.println("[shopsignscanner] 迁移: 重算 " + staleKeys
					+ " 行的位置键（旧键不含服务器，存在跨服覆盖风险）");
		}

		// 8) v2.1：land / gateway 列。⚠️ 必须在索引之前加列——先建 idx_land 会报
		//    "Key column 'land' doesn't exist"（2026-09-26 迁移顺序事故）
		if (!columns.contains("land")) {
			exec("ALTER TABLE shop_signs ADD COLUMN `land` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属领地（Lands），野外为空串'");
			columns.add("land");
			System.out.println("[shopsignscanner] 迁移: 新增 land 列");
		}
		if (!columns.contains("gateway")) {
			exec("ALTER TABLE shop_signs ADD COLUMN `gateway` VARCHAR(192) NOT NULL DEFAULT '' COMMENT '最近港口/驿站及距离'");
			columns.add("gateway");
			System.out.println("[shopsignscanner] 迁移: 新增 gateway 列");
		}

		// 9) 补齐查询索引（网站的所有筛选都依赖它们）
		Set<String> indexes = queryIndexNames();
		for (String[] idx : EXPECTED_INDEXES) {
			String name = idx[0];
			if (indexes.contains(name)) continue;
			exec("ALTER TABLE shop_signs ADD INDEX " + name + " " + idx[1]);
			System.out.println("[shopsignscanner] 迁移: 新增索引 " + name);
		}
	}

	// -----------------------------------------------------------------
	// information_schema 查询
	// -----------------------------------------------------------------

	private Set<String> queryColumnNames() throws Exception {
		Set<String> out = new HashSet<>();
		String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, TABLE);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.add(rs.getString(1).toLowerCase());
			}
		}
		return out;
	}

	private Map<String, String> queryColumnTypes() throws Exception {
		Map<String, String> out = new java.util.HashMap<>();
		String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, TABLE);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) out.put(rs.getString(1).toLowerCase(), rs.getString(2).toLowerCase());
			}
		}
		return out;
	}

	private Set<String> queryColumnCharset(String column) throws Exception {
		Set<String> out = new HashSet<>();
		String sql = "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, TABLE);
			ps.setString(2, column);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String cs = rs.getString(1);
					if (cs != null) out.add(cs.toLowerCase());
				}
			}
		}
		return out;
	}

	private Set<String> queryIndexNames() throws Exception {
		Set<String> out = new HashSet<>();
		String sql = "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, TABLE);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String name = rs.getString(1);
					if (name != null) out.add(name.toLowerCase());
				}
			}
		}
		return out;
	}

	private static boolean isVarchar(String dataType) {
		return dataType != null && (dataType.contains("char") || dataType.contains("text"));
	}

	/** 执行一个返回单个整数的查询（用于幂等探测） */
	private int queryInt(String sql) throws Exception {
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : 0;
		}
	}

	private void exec(String sql) throws Exception {
		try (Statement st = connection.createStatement()) {
			st.executeUpdate(sql);
		}
	}

	/**
	 * 回读已有记录用于本地去重。
	 *
	 * 2.2.2 起带上 land/gateway：扫描器的去重键是 (raw, land, gateway) 三元组，
	 * 只回读 raw 会让每一行在首次扫到时都被判为「有变化」而重写一遍
	 * （首轮大量无意义 UPDATE）。带上后只有真正的变化才会触发写入。
	 */
	private void loadExistingEntries() throws Exception {
		List<String[]> entries = new ArrayList<>();
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT location_key, raw, land, gateway FROM shop_signs")) {
			while (rs.next()) {
				entries.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
			}
		}
		if (onEntriesLoaded != null && !entriesDelivered) {
			onEntriesLoaded.accept(entries);
			entriesDelivered = true;
		}
	}

	// -----------------------------------------------------------------
	// 写入
	// -----------------------------------------------------------------

	/**
	 * 把一条记录绑到 PreparedStatement 上。
	 *
	 * <p>单行 INSERT 与批量 INSERT 共用这一套绑定逻辑：两条路径对同一记录
	 * 必须产生完全相同的 SQL 参数，否则失败回退会得到不同结果。
	 *
	 * <p>下标从 1 开始；批量版在第 N 行之前没有额外参数，所以行间偏移 = N * 19。
	 */
	private static void bind(PreparedStatement ps, ShopRecord r, int offset) throws Exception {
		String now = r.time();
		// seen_at：内容变化时间
		ps.setString(offset + 1, now);
		// first_seen_at：首次收录（冲突时不更新）
		ps.setString(offset + 2, now);
		// last_seen_at
		ps.setString(offset + 3, now);
		// 3) 超长字段入库前按列宽截断（2.2.2 修复）。
		//
		// 牌面是玩家自造的：物品名/店主名可以塞到 64 字符以上，raw 更是四行原文
		// 无上限拼接。不截断的话整条 INSERT 抛 Data truncation，
		// 在 drainBatch 的分拣逻辑里算「数据错误」→ 整条丢弃 → 商店凭空消失。
		// 截断只丢尾部字符，商店还在；截断在入库前做而不是让数据库做，
		// 是为了避免写入失败路径（重连/退避）被一条文案超长的牌子触发。
		ps.setString(offset + 4, clamp(r.owner(), OWNER_MAX));
		ps.setString(offset + 5, clamp(r.type(), TYPE_MAX));
		ps.setInt(offset + 6, r.quantity());
		ps.setString(offset + 7, clamp(r.item(), ITEM_MAX));
		// 价格用 BigDecimal 写入 DECIMAL 列：避免 double 的二进制表示误差落库。
		//
		// 防御性校验（2.2.1）：DECIMAL(20,4) 最多 16 位整数，超出会让 MySQL 抛
		// Data truncation。这类记录重试永远不会成功，必须在写入前就判为数据错误，
		// 由 drainBatch 丢弃 —— 否则它会永久占据队列并反复触发 60 秒重连退避。
		if (!Double.isFinite(r.price()) || Math.abs(r.price()) >= MAX_DB_PRICE) {
			throw new java.sql.SQLDataException(
					"价格超出 DECIMAL(20,4) 可表示范围: " + r.price() + "（牌面原文: " + r.priceRaw() + "）");
		}
		ps.setBigDecimal(offset + 8, BigDecimal.valueOf(r.price()));
		ps.setString(offset + 9, clamp(r.priceRaw(), PRICE_RAW_MAX));
		ps.setString(offset + 10, clamp(r.world(), WORLD_MAX));
		ps.setInt(offset + 11, r.x());
		ps.setInt(offset + 12, r.y());
		ps.setInt(offset + 13, r.z());
		ps.setString(offset + 14, r.side());
		ps.setString(offset + 15, clamp(r.server(), SERVER_MAX));
		ps.setString(offset + 16, clamp(r.raw(), RAW_MAX));
		ps.setString(offset + 17, clamp(r.land(), LAND_MAX));
		ps.setString(offset + 18, clamp(r.gateway(), GATEWAY_MAX));
		ps.setString(offset + 19, r.locationKeyMd5());
	}

	/** 单条入库（逐行回退路径用；正常路径走 {@link #insertBatch}） */
	private void insert(ShopRecord r) throws Exception {
		try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
			bind(ps, r, 0);
			ps.executeUpdate();
		}
	}

	/**
	 * 批量入库（2.2.2）：一条多行 INSERT 提交整批。
	 *
	 * <p>调用方必须保证 {@code records} 非空。
	 * 失败时抛出的异常由 {@link #drainBatch()} 决定下一步 —— 但注意：
	 * 批量失败<em>不</em>自动回退逐行，因为无法定位坏行。回退决策在
	 * {@link #insertBatchOrFallback} 里做，那里才拿得到逐条的记录。
	 */
	private void insertBatch(List<ShopRecord> records) throws Exception {
		try (PreparedStatement ps = connection.prepareStatement(INSERT_BATCH_SQL)) {
			for (int i = 0; i < records.size(); i++) {
				bind(ps, records.get(i), i * INSERT_FIELDS);
			}
			ps.executeUpdate();
		}
	}

	/**
	 * 先批量，失败再逐行。
	 *
	 * <p>返回成功入库的条数。整批一条都没成功时抛出异常，让上层走重连退避。
	 *
	 * <p>这里的关键取舍：批量失败既可能是连接问题（重连就好），
	 * 也可能是某一行数据问题（重试一万次也没用）。逐条重跑一次就能区分，
	 * 而成本只在失败路径上发生 —— 正常路径一次批量就完成。
	 *
	 * <p><b>计数器的归属</b>：数据错误丢弃条数走 {@link #discardedCount}，
	 * 但 {@link #queuedCount} 一律不动 —— 上下减由 {@link #drainBatch} 统一做
	 * （整批都要出队），在这里减会重复扣。
	 *
	 * <p>单条批不特殊处理逐行：那就是它自己，直接抛给上层分拣。
	 */
	private int insertBatchOrFallback(List<ShopRecord> records) throws Exception {
		try {
			insertBatch(records);
			return records.size();
		} catch (Exception batchError) {
			if (records.size() == 1) {
				// 单条 = 就是它自己，按原来的逻辑抛给上层分拣。
				throw batchError;
			}
			System.err.println("[shopsignscanner] 批量入库失败，回退逐条重试: " + shortMessage(batchError));
			int succeeded = 0;
			Exception pending = null;
			for (ShopRecord r : records) {
				try {
					insert(r);
					succeeded++;
				} catch (Exception e) {
					if (isDataError(e)) {
						// 数据错误：跳过这一条，继续试其余的。
						// 台账行的确认在 drainBatch 统一做（它拿得到 outboxSeq）。
						discardedCount++;
						System.err.println("[shopsignscanner] 丢弃无法入库的记录（不再重试）: "
								+ describeRecord(r) + " ｜ " + shortMessage(e));
						continue;
					}
					pending = e;
					break;
				}
			}
			if (succeeded == 0 && pending != null) throw pending;
			return succeeded;
		}
	}

	/**
	 * 按列宽安全截断（按码点，见 {@link com.shopsignscanner.ColumnLimits#clamp}）。
	 *
	 * <p>注意要先截断再 setString：setString 之后数据库已经在执行时才报错，
	 * 而截断发生在发送之前，永不触发 Data truncation。
	 */
	private static String clamp(String s, int limit) {
		return com.shopsignscanner.ColumnLimits.clamp(s, limit);
	}

	/**
	 * 取一份「本次该随上传批带走的」心跳键快照。
	 *
	 * <p>与 {@link #maybeFlushHeartbeat()} 的取值逻辑同源（5 分钟间隔 + ±20% 抖动、
	 * 取快照不 removeAll），差别只在「不立刻发」：键会跟着下一批 shops 一起 POST，
	 * 由 {@link #drainBatch()} 在投递成功后 {@code removeAll}。
	 *
	 * <p>为什么不让每批都带：一个服务器 500 块牌子，扫描间隔 10 秒的话，
	 * 每分钟就有 6 次批量上传，每次都带 500 个 key 等于把 body 撑大 5 倍以上，
	 * 而绝大多数 key 的 last_seen_at 刚刚才刷新过。攒 5 分钟发一次，
	 * 站点侧 touchLastSeen 的语义完全不受影响。
	 *
	 * @return 快照（可能为空），调用方负责 {@code removeAll} 或整份保留
	 */
	private List<String> pollHeartbeatKeysForBatch() {
		if (pendingHeartbeat.isEmpty()) return null;
		long now = System.currentTimeMillis();
		long interval = (long) (HEARTBEAT_INTERVAL_MS
				* (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
		if (lastHeartbeatAt != 0 && now - lastHeartbeatAt < interval) return null;
		lastHeartbeatAt = now;
		List<String> keys = new ArrayList<>(pendingHeartbeat);
		return keys.isEmpty() ? null : keys;
	}

	/**
	 * 到点就批量刷新 last_seen_at。
	 *
	 * <p>分批是为了避免单条 SQL 过长（IN 列表上限受 max_allowed_packet 限制）。
	 *
	 * <p><b>间隔带 ±20% 抖动</b>（2.2.2）：几百台客户端的心跳间隔如果完全相同，
	 * 它们的批量 UPDATE 会精确地落在同一秒里 ——
	 * 数据库看到的不是持续的低频写入，而是每 5 分钟一次的全表更新尖峰。
	 * 抖动把尖峰摊平成一片波浪。
	 *
	 * <p><b>失败必须把键放回去</b>（2.2.2 修复）：原实现先在 {@code pendingHeartbeat}
	 * 里 removeAll，再逐批 UPDATE。任一批抛异常（连接断了、锁等待超时），
	 * 已经 remove 的键就跟着本次心跳一起消失 —— 这些商店在数据库里的
	 * last_seen_at 停在旧值，网站会认为它们已经下线，而它们明明还在。
	 * 心跳键与上传队列一样是「脏数据」，失败的唯一正确处置是重新入队。
	 *
	 * <p><b>2.2.3 起 api 模式不走这里</b>：心跳改随普通 upsert 批量发出
	 * （一次 POST 的 body 里既有 shops 也有 heartbeatKeys，站点做 touchLastSeen），
	 * 所以本方法在 api 模式下是空操作。攒 key 的 {@link #markSeen} 与
	 * {@code HEARTBEAT_INTERVAL_MS} 的 5 分钟间隔都保留 ——
	 * 只是「取快照」这一步挪到了 {@link #pollHeartbeatKeysForBatch()}。
	 */
	private void maybeFlushHeartbeat() throws Exception {
		if (config != null && config.isApiMode()) return;
		if (pendingHeartbeat.isEmpty()) return;
		long now = System.currentTimeMillis();
		// 抖动后的实际间隔在 HEARTBEAT_INTERVAL_MS 的 80% ~ 120% 之间。
		// 每次重算而不是存起来：重算的结果是独立随机的，不会形成同步的周期。
		long interval = (long) (HEARTBEAT_INTERVAL_MS
				* (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
		if (lastHeartbeatAt != 0 && now - lastHeartbeatAt < interval) return;

		// 取快照。注意这里**不** removeAll：只有整批都成功才算真的交付。
		// 期间新扫到的键继续累积，不会和快照重复计数（PendingKeys 是 Set）。
		List<String> keys = new ArrayList<>(pendingHeartbeat);
		if (keys.isEmpty()) return;

		String timestamp = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

		int touched = 0;
		try {
			for (int start = 0; start < keys.size(); start += HEARTBEAT_CHUNK) {
				List<String> chunk = keys.subList(start, Math.min(start + HEARTBEAT_CHUNK, keys.size()));
				StringBuilder sql = new StringBuilder(HEARTBEAT_SQL_PREFIX);
				for (int i = 0; i < chunk.size(); i++) {
					if (i > 0) sql.append(',');
					sql.append('?');
				}
				sql.append(')');
				try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
					ps.setString(1, timestamp);
					for (int i = 0; i < chunk.size(); i++) {
						ps.setString(i + 2, chunk.get(i));
					}
					touched += ps.executeUpdate();
				}
			}
		} catch (Exception e) {
			// 回滚：把没送成功的键放回待心跳集合，等下一轮（最多 5 分钟）重试。
			// 只回滚快照里剩下的部分会更加精确，但 chunks 是 subList 视图、
			// 失败的边界难以定位；心跳是幂等的全量重放，成本低于漏掉一整个心跳周期。
			pendingHeartbeat.addAll(keys);
			System.err.println("[shopsignscanner] 心跳失败（" + keys.size() + " 个存活键已保留，下轮重试）: " + e);
			throw e;
		}

		lastHeartbeatAt = now;
		heartbeatCount++;
		// 全部批成功后才摘键。只摘快照里的这些：扫描期间新扫到的键继续留给下一轮。
		pendingHeartbeat.removeAll(keys);
		if (touched > 0) {
			System.out.println("[shopsignscanner] 心跳: 刷新 " + touched + " 个商店的存活时间");
		}
	}

	private void closeConnection() {
		if (connection != null) {
			try {
				connection.close();
			} catch (Exception ignored) {
			}
			connection = null;
		}
	}

	private static String shortMessage(Exception e) {
		String m = e.getMessage();
		if (m == null) return e.getClass().getSimpleName();
		return m.length() > 80 ? m.substring(0, 80) : m;
	}

	/** 供状态展示：当前累积待心跳的商店数 */
	public int pendingHeartbeatCount() {
		return pendingHeartbeat.size();
	}

	/**
	 * 供状态展示：上传台账里未确认入库的条数。
	 *
	 * <p>大于 0 表示有记录堆在磁盘上等队列空位（正常补传中），
	 * 持续增长才说明上传速率跟不上扫描速率。
	 */
	public int pendingDiskCount() {
		return pendingDiskCount;
	}

	/** 供状态展示：从上传台账补回队列的累计条数 */
	public int refilledCount() {
		return refilledCount;
	}

	/**
	 * 异步读取全部商店（查询面板数据源，2.2.0 起本地 txt 已移除）。
	 *
	 * <p>⚠️ 不能把查询任务排进上传执行器——workerLoop 是永不退出的循环，
	 * 独占单线程，后排队的查询永远轮不到（2026-09-26 事故）。这里用
	 * 独立的短生命周期线程 + 独立连接（用完即关），与上传线程完全隔离，
	 * 不存在连接的跨线程共用问题。
	 *
	 * <p>2.2.2 起带 {@value #FETCH_CACHE_MS} 毫秒结果缓存：面板每开一次都会读一次
	 * 全表（当前约数万行），而商店数据在几秒内不可能有变化。反复开关面板等于
	 * 反复付一次「新连接 + 全表扫描 + 数万个 ShopRecord 分配」的钱，并把
	 * 鼠标点开面板到出现列表之间的卡顿时间放大到几秒。
	 * 缓存命中直接复用上次结果，不再碰数据库。
	 */
	public void fetchShopsAsync(java.util.function.BiConsumer<List<ShopRecord>, String> callback) {
		List<ShopRecord> cached = shopsCache;
		if (cached != null && System.currentTimeMillis() - shopsCacheAt < FETCH_CACHE_MS) {
			callback.accept(cached, null);
			return;
		}
		if (fetchInFlight) {
			// 已有同一次查询在跑。重复开一次连接只会让两边都变慢，
			// 也让面板在结果到达前先闪一次「空列表」。
			pendingFetchCallbacks.add(callback);
			return;
		}
		fetchInFlight = true;
		pendingFetchCallbacks.add(callback);
		Thread fetchThread = new Thread(() -> {
			List<ShopRecord> out = new ArrayList<>();
			String error = null;
			try (Connection conn = DriverManager.getConnection(jdbcUrl(), config.dbUser, config.dbPassword);
					Statement st = conn.createStatement();
					ResultSet rs = st.executeQuery("SELECT seen_at, owner, type, quantity, item, price, price_raw, "
							+ "world, x, y, z, side, server, raw, land, gateway FROM shop_signs ORDER BY seen_at DESC")) {
				while (rs.next()) {
					out.add(new ShopRecord(
							rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
							rs.getString(5), rs.getDouble(6), rs.getString(7), rs.getString(8),
							rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getString(12),
							rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16)));
				}
			} catch (Exception e) {
				error = shortMessage(e);
			}
		if (error == null) {
			// 只缓存成功结果：把失败缓存下来会让面板在网络恢复后仍显示错误。
			shopsCache = out;
			shopsCacheAt = System.currentTimeMillis();
		}
		fetchInFlight = false;
		// 等待中的调用方一次性全部交付（它们拿到的是同一批数据）
		List<java.util.function.BiConsumer<List<ShopRecord>, String>> waiting =
				new ArrayList<>(pendingFetchCallbacks);
		pendingFetchCallbacks.clear();
		for (var cb : waiting) cb.accept(out, error);
	}, "shopsignscanner-Fetch");
		fetchThread.setDaemon(true);
		fetchThread.start();
	}

	private String jdbcUrl() {
		return "jdbc:mysql://" + config.dbHost + ":" + config.dbPort + "/" + config.dbDatabase
				+ "?connectTimeout=8000&socketTimeout=15000&characterEncoding=UTF-8"
				+ "&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
	}

	/** 保留给未来扩展：一次心跳里涉及的位置键（测试与排障用） */
	Set<String> snapshotHeartbeatKeys() {
		return new LinkedHashSet<>(pendingHeartbeat);
	}
}
