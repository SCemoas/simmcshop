package com.shopsign.scanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.shopsignscanner.ContentFingerprint;

/**
 * 扫描玩家周围已加载区块中的告示牌，解析并按位置去重（2.2.0 起：记录只走数据库，本地 txt 已移除）。
 * 同一坐标、同一面的商店首次记录为新增；内容变化时判定为更新（数据库覆盖更新）。
 *
 * <p>另外每次扫描都会把「本次看到的全部商店位置键」交给
 * {@code onScanComplete}，用于心跳刷新数据库里的存活时间（last_seen_at）。
 * 没有这一步，内容一直没变的商店在数据库里就等于「从未再被看到」，
 * 商店被拆掉后网站会永远显示它。
 *
 * <p><b>性能</b>：客户端在扫描间隔内反复扫到同一批牌子，解析（正则清洗、字符串拼接）
 * 是模组最大的周期性开销。这里以 {@code SignText} 的<strong>对象引用</strong>做缓存键 ——
 * 服务端只在牌子内容变化时才下发新的 SignText 实例，引用相同即内容必然相同，
 * 命中时零解析零分配（只补记心跳键）。领地数据刷新时会主动清缓存，让领地列随新数据重算。
 */
public class SignScanner {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	/** textCache 按距离淘汰的最小间隔：淘汰要遍历整表，不能每轮扫描都做 */
	private static final long EVICT_INTERVAL_MS = 60_000L;

	private final ModConfig config;
	/**
	 * 位置键MD5 -> 最近一次产出内容的内容指纹。
	 *
	 * 数据源是数据库回读（{@link #addSeenEntry}）。从 2.2.2 起判定内容用三项：
	 * 只看 raw 的话，「内容没变但领地变了」会永远判为「没变」而 return 0，
	 * 数据库里的 land/gateway 列就停在旧值上（领地改名、领地数据刷新、
	 * 最近港口变化都属于这一类）—— 2.2.0 领地列上线后一直没被发现，
	 * 因为牌面内容不变是最常见的情况。
	 *
	 * <p><b>存的是 32 位指纹而不是原文</b>（2.2.2）：一张牌的 raw 是四行牌面
	 * 无缝拼接，平均几十字节，加上 land/gateway 两条，数万个已见商店就是
	 * 几 MB 常驻堆。而这些原文的唯一用途就是和下一次的产出比对是否相同 ——
	 * 比对只需要固定长度摘要。
	 *
	 * <p>用 32 位而不是更长的摘要：宽度直接决定内存占用（10 万个商店 × 16 字节
	 * = 1.6 MB，× 4 字节 = 0.4 MB）。代价是理论上有 2^-32 的概率把一次真实改动
	 * 误判为「没变」而漏上传。这个量级可以接受：同一个牌面被反复改写、
	 * 且改写后内容指纹正好撞上旧指纹，才会触发。
	 */
	private final Map<String, ContentFingerprint> seen = new HashMap<>();
	private final LandResolver landResolver;
	private final BiConsumer<ShopRecord, Boolean> onNewRecord;
	/** 每轮扫描结束后的回调，参数是本次看到的所有商店位置键 */
	private final Consumer<Set<String>> onScanComplete;
	/**
	 * SignText 引用缓存：pos.asLong()*2+背面 标记 -> (文本引用, 位置键MD5)。
	 *
	 * 必须是并发容器：渲染线程在 tryRecordSide 里 get/put，
	 * 而领地数据刷新后由 shopsignscanner-Lands 线程调用 clearTextCache() 清空。
	 * 用普通 HashMap 会出现两个线程同时改内部结构的数据竞争
	 * （2.2.1 修复；表现为缓存偶发损坏/丢失，极端情况下可能卡住渲染线程）。
	 */
	private final Map<Long, SignCacheEntry> textCache = new ConcurrentHashMap<>();
	/**
	 * textCache 上一次按距离淘汰的位置与时间。
	 *
	 * <p>缓存只增不淘汰会缓慢泄漏：玩家在一个区域扫完的牌子，走远之后
	 * 对应的 SignText 引用永远留在地图里（{@code pos.asLong()*2+sideBit} 是位置键，
	 * 与玩家在哪无关）。一天探索下来就是几十万条目，还会让
	 * {@link #clearTextCache} 之外的每次查找都更慢。
	 *
	 * <p>淘汰只在扫描线程（render 线程）上做，与 put/get 同一个线程，
	 * 不需要额外同步；但阈值判读用 volatile 保证可见性。
	 */
	private volatile long evictAt = -1L;
	private volatile int evictedByDistance;
	private ClientLevel cacheLevel;

	// ---- 2.2.2 分 tick 扫描的执行态（仅渲染线程访问，无需同步） ----
	/** null = 当前没有进行中的扫描 */
	private ClientLevel scanLevel;
	private String scanServer;
	private Vec3 scanCenter;
	private int scanChunkX, scanChunkZ, scanChunkMinX, scanChunkMinZ, scanChunkMaxX, scanChunkMaxZ;
	private double scanRadiusSq;
	private int scanAdded;
	/** {@link #scan(Minecraft)} / {@link #tickScan(Minecraft)} 的返回值：上一轮新增记录数 */
	private int lastAdded;
	/** 一次扫描每 tick 推进的区块数：与半径无关，保证单 tick 成本恒定 */
	private static final int CHUNKS_PER_TICK = 3;
	/** 开始一轮扫描时的区块坐标，用于检测玩家是否已移动到缓存范围之外 */
	private int startChunkX, startChunkZ;
	/** 上一次 {@link #resumeScan} 完成的时刻，用于防止分帧扫描永不结束 */
	private long lastResumeAt;
	/** 每轮扫描复用的「本次看到的商店」集合（避免每 5 秒新建集合） */
	private final Set<String> presentKeys = new HashSet<>();
	private volatile int totalRecorded;

	public SignScanner(ModConfig config, LandResolver landResolver,
			BiConsumer<ShopRecord, Boolean> onNewRecord, Consumer<Set<String>> onScanComplete) {
		this.config = config;
		this.landResolver = landResolver;
		this.onNewRecord = onNewRecord;
		this.onScanComplete = onScanComplete;
	}

	/** 缓存的牌子：文本引用 + 预计算的位置键 MD5 */
	private record SignCacheEntry(SignText text, String keyMd5) {
	}

	/**
	 * 合并一条已知记录（启动时从数据库回读）。
	 *
	 * @param key   位置键MD5
	 * @param raw   牌面原文
	 * @param land  领地（旧行为只传 raw，land/gateway 传 null 时回读后第一次
	 *              扫到必然判为「有变化」并重写一行 —— 这正是我们想要的行为：
	 *              没人 2.2.2 之前的库里 land/gateway 列是不刷新的）
	 */
	public synchronized void addSeenEntry(String key, String raw) {
		addSeenEntry(key, raw, "", "");
	}

	/** 合并一条已知记录（带 land/gateway 的完整形式） */
	public synchronized void addSeenEntry(String key, String raw, String land, String gateway) {
		if (seen.putIfAbsent(key, ContentFingerprint.of(raw, land, gateway)) == null) totalRecorded++;
	}

	/** 领地数据刷新后调用：清空文本缓存，让下一轮扫描用新领地数据重算 land/gateway */
	public void clearTextCache() {
		textCache.clear();
		evictAt = -1L;
	}

	/** 已被距离淘汰清掉的缓存条数（诊断用） */
	public int evictedByDistance() {
		return evictedByDistance;
	}

	/** 上一轮扫描新增的记录数（诊断/HUD 用） */
	public int lastScanAdded() {
		return lastAdded;
	}

	/**
	 * 执行一次完整扫描，返回本次新增的记录数。
	 *
	 * <p>仅用于手动扫描（按键触发）与配置保存后的一次性重扫：
	 * 它会把半径内所有区块一口气遍历完，一个大尖刺换一个即时反馈。
	 * 自动扫描走 {@link #tickScan}，没有这个尖刺。
	 */
	public int scan(Minecraft minecraft) {
		if (beginScan(minecraft) == null) return 0;
		try {
			while (!scanDone()) {
				resumeScan(64);
			}
		} finally {
			endScan();
		}
		return lastAdded;
	}

	/**
	 * 开始一轮扫描前的准备：白名单校验、换世界清缓存、按距离淘汰、初始化游标。
	 *
	 * @return null 表示本轮应当跳过（不在白名单服务器、玩家不在世界里）；
	 *         否则返回本次扫描的半径，调用方据此计算每 tick 的进度
	 */
	private Integer beginScan(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) return null;

		// 服务器白名单：默认放行全部（可配置），其他服务器零开销跳过
		String server = currentServer(minecraft);
		if (!config.isServerAllowed(server)) return null;

		if (level != cacheLevel) {
			// 换世界后坐标长整型会撞车，缓存必须清空
			textCache.clear();
			cacheLevel = level;
			evictAt = -1L;
		}
		presentKeys.clear();

		scanCenter = minecraft.player.position();
		scanServer = server;
		scanLevel = level;
		int radius = config.radius;
		maybeEvictFarEntries(scanCenter, radius);

		scanChunkX = scanChunkMinX = (int) Math.floor((scanCenter.x - radius) / 16.0);
		scanChunkMaxX = (int) Math.floor((scanCenter.x + radius) / 16.0);
		scanChunkZ = scanChunkMinZ = (int) Math.floor((scanCenter.z - radius) / 16.0);
		scanChunkMaxZ = (int) Math.floor((scanCenter.z + radius) / 16.0);
		scanRadiusSq = (double) radius * radius;
		startChunkX = (int) Math.floor(scanCenter.x / 16.0);
		startChunkZ = (int) Math.floor(scanCenter.z / 16.0);
		lastResumeAt = System.currentTimeMillis();
		scanAdded = 0;
		return radius;
	}

	/**
	 * 推进扫描：最多再处理 {@code maxChunks} 个区块。
	 *
	 * <p><b>2.2.2 的关键改动</b>：旧实现一次 {@code scan()} 就把半径内
	 * 全部 81 个区块遍历完，全发生在渲染线程的一个 tick 里。
	 * 大量扫描时（半径 64 = 81 区块、每区块几十个方块实体）这就是
	 * 一次几十毫米的卡顿，表现为 fps 周期性骤降。
	 *
	 * <p>现在把遍历拆成小片，由调用方（客户端 tick 回调）每 tick 推进一小步：
	 * <ul>
	 *   <li>每 tick 处理的区块数 = {@link #CHUNKS_PER_TICK}，与半径无关；</li>
	 *   <li>遇到玩家位置明显移动（换了一个区块）时主动作废本轮，
	 *       避免「扫的是刚才那片地方」的过期结果；</li>
	 *   <li>{@code scanDone} 表示本轮已遍历完，下一 tick 重新开始。</li>
	 * </ul>
	 *
	 * @return 本次推进实际处理的区块数（已结束或无需推进时返回 0）
	 */
	private int resumeScan(int maxChunks) {
		if (scanLevel == null || scanChunkX > scanChunkMaxX) return 0;
		int processed = 0;
		while (processed < maxChunks && scanChunkX <= scanChunkMaxX) {
			int cx = scanChunkX;
			int cz = scanChunkZ;
			advanceChunkCursor();
			processed++;
			if (!scanLevel.hasChunk(cx, cz)) continue;
			LevelChunk chunk = scanLevel.getChunk(cx, cz);
			for (var blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof SignBlockEntity sign)) continue;
				if (config.oakOnly && !isOakSign(sign.getBlockState().getBlock())) continue;
				BlockPos pos = sign.getBlockPos();
				double dx = pos.getX() + 0.5 - scanCenter.x;
				double dy = pos.getY() + 0.5 - scanCenter.y;
				double dz = pos.getZ() + 0.5 - scanCenter.z;
				if (dx * dx + dy * dy + dz * dz > scanRadiusSq) continue;
				scanAdded += tryRecordSide(sign.getFrontText(), "front", sign, scanLevel, scanServer);
				scanAdded += tryRecordSide(sign.getBackText(), "back", sign, scanLevel, scanServer);
			}
		}
		return processed;
	}

	/** 区块游标前进一步（列优先，让同一 tick 内的区块在空间上连续） */
	private void advanceChunkCursor() {
		if (++scanChunkZ <= scanChunkMaxZ) return;
		scanChunkZ = scanChunkMinZ;
		scanChunkX++;
	}

	/** 一轮扫描遍历完成：把存活商店交给心跳，结束本轮 */
	private void endScan() {
		lastAdded = scanAdded;
		if (onScanComplete != null && !presentKeys.isEmpty()) {
			onScanComplete.accept(presentKeys);
		}
		scanLevel = null;
	}

	/** 一轮扫描是否已经遍历到最后一个区块 */
	private boolean scanDone() {
		return scanLevel == null || scanChunkX > scanChunkMaxX;
	}

	/**
	 * 由客户端 tick 回调驱动的分帧扫描：没有任何单次调用会遍历整个半径。
	 *
	 * <p>设计要点：
	 * <ul>
	 *   <li>进行中的扫描每次调用只推 {@link #CHUNKS_PER_TICK} 个区块，
	 *       单次成本与扫描半径无关；</li>
	 *   <li>玩家跨出开始扫描的区块 {@value #MAX_DRIFT_CHUNKS} 个以上时
	 *       作废本轮（{@code presentKeys} 也清空，不会把过期存活集当心跳），
	 *       下一 tick 在新位置重新开始；</li>
	 *   <li>一轮结束后调用方会在下一个节拍开始新一轮，
	 *       覆盖频率与旧的「每 intervalTicks 全量扫描」一致，但不再有尖刺。</li>
	 * </ul>
	 *
	 * @return 本轮扫描到目前为止新增的记录数（未结束时返回 0）
	 */
	public int tickScan(Minecraft minecraft) {
		if (scanLevel != null) {
			if (shouldAbandonScan(minecraft)) {
				scanLevel = null;
				presentKeys.clear();
			} else {
				resumeScan(CHUNKS_PER_TICK);
				if (!scanDone()) return 0;
				endScan();
				return lastAdded;
			}
		}
		if (beginScan(minecraft) == null) return 0;
		resumeScan(CHUNKS_PER_TICK);
		if (scanDone()) {
			endScan();
			return lastAdded;
		}
		return 0;
	}

	/** 玩家跨出开始扫描的区块这么多格以上就作废本轮 */
	private static final int MAX_DRIFT_CHUNKS = 4;

	private boolean shouldAbandonScan(Minecraft minecraft) {
		if (minecraft.level != scanLevel || minecraft.player == null) return true;
		return Math.abs(minecraft.player.getBlockX() >> 4) - startChunkX > MAX_DRIFT_CHUNKS
				|| Math.abs(minecraft.player.getBlockX() >> 4) - startChunkX < -MAX_DRIFT_CHUNKS
				|| Math.abs(minecraft.player.getBlockZ() >> 4) - startChunkZ > MAX_DRIFT_CHUNKS
				|| Math.abs(minecraft.player.getBlockZ() >> 4) - startChunkZ < -MAX_DRIFT_CHUNKS;
	}

	/** 是否有一轮扫描正在进行：供 HUD 显示"扫描中" */
	public boolean scanInProgress() {
		return scanLevel != null;
	}

	/**
	 * 淘汰距离超过两倍扫描半径的缓存条目。
	 *
	 * <p>为什么是「两倍半径」而不是「一倍」：
	 * 缓存是为「同一区域反复扫描」服务的。玩家贴着半径边缘走动时，
	 * 边缘外的牌子下一秒就会重新进入扫描范围，用一倍半径淘汰会
	 * 每次都在边界上抖进抖出（刚删就又重新解析）。
	 * 两倍半径给了一个缓冲带，只有玩家真的离开了这片区域才清理。
	 *
	 * <p>节流到每 {@value #EVICT_INTERVAL_MS} 毫秒一次：淘汰本身要遍历整张表，
	 * 不能每轮扫描都做（扫描间隔通常只有几秒）。用「玩家位置变化」作为补偿：
	 * 位移小于阈值时直接跳过，所以静止挂机时完全不付这份开销。
	 */
	private void maybeEvictFarEntries(Vec3 center, int radius) {
		long now = System.currentTimeMillis();
		long at = evictAt;
		if (at != -1L && now - at < EVICT_INTERVAL_MS) return;

		double evictRadiusSq = (double) (2 * radius) * (2 * radius);
		int removed = 0;
		for (var it = textCache.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next().getKey();
			// cacheKey = pos.asLong()*2 + sideBit，除掉 sideBit 拿回 asLong，
			// 再用 BlockPos.of 还原坐标。这里不新建 BlockPos（省一次分配）。
			long raw = entry >> 1;
			int x = (int) (raw >> 38);
			int y = (int) (raw << 26 >> 52);
			int z = (int) (raw << 52 >> 38);
			double dx = x + 0.5 - center.x;
			double dy = y + 0.5 - center.y;
			double dz = z + 0.5 - center.z;
			if (dx * dx + dy * dy + dz * dz > evictRadiusSq) {
				it.remove();
				removed++;
			}
		}
		evictedByDistance += removed;
		evictAt = now;
	}

	private int tryRecordSide(SignText text, String side, SignBlockEntity sign, ClientLevel level, String server) {
		// 缓存命中：同一 SignText 引用 = 内容必然未变。跳过解析，只补记心跳键。
		// keyMd5 为空串表示「黑名单领地」：不记心跳、不入库。
		long cacheKey = sign.getBlockPos().asLong() * 2 + ("back".equals(side) ? 1L : 0L);
		SignCacheEntry cached = textCache.get(cacheKey);
		if (cached != null && cached.text == text) {
			if (!cached.keyMd5().isEmpty()) presentKeys.add(cached.keyMd5);
			return 0;
		}

		Optional<ShopSignParser.ParsedSign> parsed = ShopSignParser.parse(plainLines(text.getMessages(false)), config);
		if (parsed.isEmpty()) return 0;

		ShopSignParser.ParsedSign p = parsed.get();
		BlockPos pos = sign.getBlockPos();
		String worldId = level.dimension().identifier().toString();
		String land = "";
		String gateway = "";
		if (config.landEnabled && landResolver != null) {
			String resolved = landResolver.landAt(worldId, pos.getX(), pos.getZ());
			if (resolved != null) land = resolved.replace('|', '/');
			String gw = landResolver.nearestGatewayText(worldId, pos.getX(), pos.getZ());
			if (gw != null) gateway = gw;
		}

		// 领地黑名单：这些领地的商店不采集、不入库、不心跳（缓存仍建，避免重复解析）
		if (config.isLandBlacklisted(land)) {
			textCache.put(cacheKey, new SignCacheEntry(text, ""));
			return 0;
		}

		ShopRecord record = new ShopRecord(
				LocalDateTime.now().format(TIME),
				p.owner(), p.type(), p.quantity(), p.item(), p.price(), p.priceRaw(),
				worldId,
				pos.getX(), pos.getY(), pos.getZ(),
				side, server, p.raw(), land, gateway);

		String key = record.locationKeyMd5();
		textCache.put(cacheKey, new SignCacheEntry(text, key));
		// 关键：先记为「本次看到」，再判断内容是否变化。
		// 内容未变时下面会 return 0，但它依然是一个存活着的商店。
		presentKeys.add(key);

		boolean isUpdate;
		synchronized (this) {
			ContentFingerprint old = seen.get(key);
			ContentFingerprint nowValue = ContentFingerprint.of(record.raw(), land, gateway);
			if (old != null && old.equals(nowValue)) return 0; // 内容与领地产出都没变
			isUpdate = old != null;
			seen.put(key, nowValue);
			if (!isUpdate) totalRecorded++;
		}
		if (onNewRecord != null) onNewRecord.accept(record, isUpdate);
		return isUpdate ? 0 : 1;
	}

	private static boolean isOakSign(Block block) {
		return block == Blocks.OAK_SIGN || block == Blocks.OAK_WALL_SIGN;
	}

	private static String[] plainLines(Component[] components) {
		String[] lines = new String[components.length];
		for (int i = 0; i < components.length; i++) {
			lines[i] = components[i] == null ? "" : components[i].getString();
		}
		return lines;
	}

	private static String currentServer(Minecraft minecraft) {
		if (minecraft.getSingleplayerServer() != null) return "singleplayer";
		var info = minecraft.getCurrentServer();
		return info != null && info.ip != null ? info.ip : "unknown";
	}

	public int totalRecorded() {
		return totalRecorded;
	}
}
