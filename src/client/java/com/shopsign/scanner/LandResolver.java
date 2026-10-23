package com.shopsign.scanner;

import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 领地解析器：从服务器 Squaremap 地图站（需自行在配置里填写 mapBaseUrl）拉取领地多边形与港口/驿站点位，
 * 提供「坐标 -> 领地名 / 最近港口」判定，用于按领地分列与面板展示。
 *
 * <p><b>性能</b>：markers.json 约 11MB，若整包读成字符串再构建完整 JSON 树，
 * 每 30 分钟会产生一次上百 MB 级的内存尖峰（GC 压力）。这里用 Gson 的
 * {@link JsonReader} 对 HTTP 响应流做<strong>流式解析</strong>，只保留最终对象
 * （领地/港口合计约 1MB），未知字段直接跳过。图层 id 可能出现在 markers 之后，
 * 因此每层先缓冲候选，读完整个图层对象后再按 id/name 归属。
 */
public class LandResolver {
	private static final Pattern LAND_NAME = Pattern.compile("color: \\{land_color\\};\">([^<]+)</span>");
	private static final Pattern LAND_NAME_FALLBACK = Pattern.compile("<span[^>]*>([^<]{1,64})</span>");
	private static final Pattern LAND_OWNER = Pattern.compile("所有者:\\s*([^.]+)\\.");

	/** 一个领地：单环多边形 + 包围盒 */
	public record Land(String name, String owner, int minX, int maxX, int minZ, int maxZ, int[] xs, int[] zs) {
	}

	/** 一个港口/驿站（transport_gateways 图层的 icon 标记） */
	public record Gateway(String name, int x, int z) {
	}

	/** 最近港口判定结果 */
	public record NearestGateway(String name, int distance) {
	}

	private volatile List<Land> lands = List.of();
	private volatile List<Gateway> gateways = List.of();	private volatile String loadedWorld = "";
	private final ModConfig config;
	private final HttpClient http;
	private Runnable onRefresh;
	private Thread refreshThread;

	public LandResolver(ModConfig config) {
		this.config = config;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	/** 数据成功刷新后的回调（用于让扫描器清缓存，重算 land/gateway 列） */
	public void setOnRefresh(Runnable callback) {
		this.onRefresh = callback;
	}

	public void start() {
		if (!config.landEnabled) return;
		if (config.mapBaseUrl == null || config.mapBaseUrl.isBlank()) {
			System.out.println("[shopsignscanner] 未配置 mapBaseUrl，跳过领地/港口判定");
			return;
		}
		refreshThread = new Thread(this::refreshLoop, "shopsignscanner-Lands");
		refreshThread.setDaemon(true);
		refreshThread.start();
	}

	private void refreshLoop() {
		while (true) {
			try {
				fetchAndParse();
			} catch (Exception e) {
				System.err.println("[shopsignscanner] 领地数据拉取失败（将定时重试）: " + e);
			}
			try {
				Thread.sleep(Math.max(5, config.landRefreshMinutes) * 60_000L);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void fetchAndParse() throws IOException, InterruptedException {
		String world = config.mapWorld == null || config.mapWorld.isBlank()
				? "minecraft_overworld" : config.mapWorld.trim();
		String base = config.mapBaseUrl == null || config.mapBaseUrl.isBlank()
				? "" : config.mapBaseUrl.trim();
		if (base.isBlank()) return;
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		String url = base + "/tiles/" + world + "/markers.json";

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(60))
				.header("User-Agent", "shopsignscanner/1.0 (Fabric Mod)")
				.GET().build();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			try (InputStream in = response.body()) {
				in.close();
			}
			throw new IOException("HTTP " + response.statusCode() + " from " + url);
		}

		List<Land> newLands = new ArrayList<>();
		List<Gateway> newGateways = new ArrayList<>();
		try (InputStream in = response.body();
				Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			parseAll(reader, newLands, newGateways);
		}

		if (!newLands.isEmpty() || !newGateways.isEmpty()) {
			this.lands = newLands;
			this.gateways = newGateways;
			this.loadedWorld = world;
			rebuildIndexes();
			System.out.println("[shopsignscanner] 领地数据已加载: " + newLands.size() + " 个领地，"
					+ newGateways.size() + " 个港口/驿站（" + world + "）");
			if (onRefresh != null) onRefresh.run();
		} else {
			System.err.println("[shopsignscanner] markers.json 中未找到领地/港口图层，保留旧数据");
		}
	}

	// -----------------------------------------------------------------
	// 流式解析
	// -----------------------------------------------------------------

	/** 一次流式解析同时提取领地与港口（顶层为图层组数组） */
	public static void parseAll(Reader reader, List<Land> landsOut, List<Gateway> gatewaysOut) throws IOException {
		JsonReader json = new JsonReader(reader);
		json.beginArray();
		while (json.hasNext()) {
			readLayerSet(json, landsOut, gatewaysOut);
		}
		json.endArray();
	}

	private static void readLayerSet(JsonReader json, List<Land> landsOut, List<Gateway> gatewaysOut) throws IOException {
		String id = null;
		String layerName = null;
		List<Land> landBuf = new ArrayList<>();
		List<Gateway> gwBuf = new ArrayList<>();
		json.beginObject();
		while (json.hasNext()) {
			String field = json.nextName();
			switch (field) {
				case "id" -> id = json.nextString();
				case "name" -> layerName = json.nextString();
				case "markers" -> readMarkers(json, landBuf, gwBuf);
				default -> json.skipValue();
			}
		}
		json.endObject();
		// 图层 id/name 可能排在 markers 之后，所以先缓冲、读完整个图层对象后再归属
		boolean isLand = "lands_world".equals(id)
				|| (id != null && id.startsWith("lands_"))
				|| "领地".equals(layerName);
		boolean isGateway = "transport_gateways".equals(id)
				|| (id != null && id.contains("gateway"))
				|| (layerName != null && layerName.contains("港口"));
		if (isLand) landsOut.addAll(landBuf);
		if (isGateway) gatewaysOut.addAll(gwBuf);
	}

	private static void readMarkers(JsonReader json, List<Land> landBuf, List<Gateway> gwBuf) throws IOException {
		json.beginArray();
		while (json.hasNext()) {
			readMarker(json, landBuf, gwBuf);
		}
		json.endArray();
	}

	private static void readMarker(JsonReader json, List<Land> landBuf, List<Gateway> gwBuf) throws IOException {
		String type = null;
		String tooltip = null;
		int[] xs = null;
		int[] zs = null;
		Integer pointX = null;
		Integer pointZ = null;

		json.beginObject();
		while (json.hasNext()) {
			String field = json.nextName();
			switch (field) {
				case "type" -> type = json.nextString();
				case "tooltip" -> tooltip = json.nextString();
				case "points" -> {
					// points 的形态随图层而异（实测至少三种）：[{x,z}...]、[[{x,z}...]]、[[[x,z]...]]。
					// 不逐形态枚举，改为通用递归收集：任意嵌套数组里出现的坐标对都收进同一个环。
					// （真实数据 4274 个领地全是单环，多环合并的近似可接受）
					json.beginArray();
					List<int[]> pts = new ArrayList<>();
					collectPoints(json, pts);
					json.endArray();
					int[][] ring = toRing(pts);
					xs = ring[0];
					zs = ring[1];
				}
				case "point" -> {
					json.beginObject();
					while (json.hasNext()) {
						String pf = json.nextName();
						switch (pf) {
							case "x" -> pointX = json.nextInt();
							case "z" -> pointZ = json.nextInt();
							default -> json.skipValue();
						}
					}
					json.endObject();
				}
				default -> json.skipValue();
			}
		}
		json.endObject();

		if ("polygon".equals(type) && tooltip != null && xs != null && xs.length >= 3) {
			String name = extractName(tooltip);
			if (name != null && !name.isBlank()) {
				landBuf.add(buildLand(name, extractOwner(tooltip), xs, zs));
			}
		} else if ("icon".equals(type) && tooltip != null && pointX != null && pointZ != null) {
			gwBuf.add(new Gateway(tooltip.trim().replace('|', '/'), pointX, pointZ));
		}
	}

	/**
	 * 递归收集任意嵌套形态的坐标：元素可能是 {x,z} 对象、[x,z] 数字对、
	 * 或更深的数组层。未知结构直接跳过，保证解析永不中断。
	 */
	private static void collectPoints(JsonReader json, List<int[]> out) throws IOException {
		while (json.hasNext()) {
			var token = json.peek();
			if (token == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
				int[] p = readPoint(json);
				if (p[0] != Integer.MIN_VALUE && p[1] != Integer.MIN_VALUE) out.add(p);
			} else if (token == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
				json.beginArray();
				if (json.peek() == com.google.gson.stream.JsonToken.NUMBER) {
					// [x, z] 数字对
					int x = json.nextInt();
					if (json.hasNext()) {
						int z = json.nextInt();
						if (x != Integer.MIN_VALUE) out.add(new int[]{x, z});
					}
					while (json.hasNext()) {
						json.skipValue();
					}
					json.endArray();
				} else {
					// 更深的嵌套层
					collectPoints(json, out);
					json.endArray();
				}
			} else {
				json.skipValue();
			}
		}
	}

	/** 读取单个 {x,z} 对象 */
	private static int[] readPoint(JsonReader json) throws IOException {
		int x = Integer.MIN_VALUE;
		int z = Integer.MIN_VALUE;
		json.beginObject();
		while (json.hasNext()) {
			String f = json.nextName();
			switch (f) {
				case "x" -> x = json.nextInt();
				case "z" -> z = json.nextInt();
				default -> json.skipValue();
			}
		}
		json.endObject();
		return new int[]{x, z};
	}

	private static int[][] toRing(List<int[]> points) {
		int[] xs = new int[points.size()];
		int[] zs = new int[points.size()];
		for (int i = 0; i < points.size(); i++) {
			xs[i] = points.get(i)[0];
			zs[i] = points.get(i)[1];
		}
		return new int[][]{xs, zs};
	}

	private static Land buildLand(String name, String owner, int[] xs, int[] zs) {
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int i = 0; i < xs.length; i++) {
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minZ = Math.min(minZ, zs[i]);
			maxZ = Math.max(maxZ, zs[i]);
		}
		return new Land(name, owner == null ? "" : owner, minX, maxX, minZ, maxZ, xs, zs);
	}

	static String extractName(String tooltip) {
		Matcher m = LAND_NAME.matcher(tooltip);
		if (m.find()) return m.group(1).trim();
		Matcher fb = LAND_NAME_FALLBACK.matcher(tooltip);
		if (fb.find()) return fb.group(1).trim();
		return null;
	}

	static String extractOwner(String tooltip) {
		Matcher m = LAND_OWNER.matcher(tooltip);
		if (m.find()) return m.group(1).trim();
		return null;
	}

	// -----------------------------------------------------------------
	// 判定
	// -----------------------------------------------------------------

	/**
	 * 判定坐标所在领地。worldId 为游戏内世界 ID（如 minecraft:overworld）。
	 * 未加载/世界不匹配/不在任何领地内返回 null。
	 */
	public String landAt(String worldId, int x, int z) {
		String worldKey = worldId == null ? "" : worldId.replace(':', '_');
		if (!worldKey.equals(loadedWorld)) return null;
		Map<Long, List<Land>> idx = landIndex;
		if (idx.isEmpty()) {
			// 索引未建（数据还没加载完）：退回线性扫描，行为不变
			return landAtIn(lands, loadedWorld, worldKey, x, z);
		}
		return landAtIndexed(idx, x, z);
	}

	/** 纯函数版判定（便于测试）：data 与 loadedWorld 需来自同一次解析 */
	public static String landAtIn(List<Land> data, String loadedWorld, String worldKey, int x, int z) {
		if (data.isEmpty() || !worldKey.equals(loadedWorld)) return null;
		for (Land land : data) {
			if (x < land.minX() || x > land.maxX() || z < land.minZ() || z > land.maxZ()) continue;
			if (pointInPolygon(x, z, land.xs(), land.zs())) return land.name();
		}
		return null;
	}

	// -----------------------------------------------------------------
	// 2.2.2 空间索引：按 16×16 chunk 分桶
	//
	// 为什么需要它：一次扫描要对半径内每个牌子的坐标各调用一次
	// landAt / nearestGateway，而两者都是对全表线性遍历。
	// 领地 4274 个、港口 895 个，一个牌面 = 5169 次候选检查；
	// 一个区块几十个牌子 × 81 区块 = 单轮扫描几十万次 AABB 比较，
	// 全部发生在渲染线程 —— 这正是「店越多 fps 越低」的直接原因。
	//
	// 做法：加载完领地/港口后，把每个对象登记进它 AABB 覆盖的所有 chunk 桶
	// （long key = (chunkX<<32)^(chunkZ&0xFFFFFFFFL)）。查询只看所在桶，
	// 复杂度从 O(领地数) 降到 O(该 chunk 上的领地数)，典型情况 1~3 个。
	//
	// 权衡：索引只建一次（fetchAndParse 成功后），查询 O(1) 桶定位。
	// 领地多边形可能很大（跨很多 chunk），桶数 = 覆盖 chunk 总数，
	// 但那是建索引一次的代价，之后每次查询都受益。
	// -----------------------------------------------------------------

	/** chunk 索引的键：把两个 32 位 chunk 坐标压进一个 long（有序保持） */
	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	/** 领地桶：chunkKey -> 该 chunk 上有 AABB 交集的领地 */
	private volatile Map<Long, List<Land>> landIndex = Map.of();

	private void rebuildIndexes() {
		landIndex = buildLandIndex(lands);
	}

	private static Map<Long, List<Land>> buildLandIndex(List<Land> lands) {
		if (lands.isEmpty()) return Map.of();
		Map<Long, List<Land>> index = new HashMap<>();
		for (Land land : lands) {
			int minCx = land.minX() >> 4;
			int maxCx = land.maxX() >> 4;
			int minCz = land.minZ() >> 4;
			int maxCz = land.maxZ() >> 4;
			// 防止异常数据（AABB 覆盖半个地图）把索引撑爆
			int span = (maxCx - minCx + 1) * (maxCz - minCz + 1);
			if (span > MAX_INDEXED_CHUNKS) continue;
			for (int cx = minCx; cx <= maxCx; cx++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					index.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(land);
				}
			}
		}
		return index;
	}

	/** 单个领地最多登记多少个 chunk（异常多边形保护） */
	private static final int MAX_INDEXED_CHUNKS = 4096;

	/** 带索引的领地判定：行为与 {@link #landAtIn} 完全一致，只是快 */
	public static String landAtIndexed(Map<Long, List<Land>> index, int x, int z) {
		List<Land> bucket = index.get(chunkKey(x >> 4, z >> 4));
		if (bucket == null) return null;
		for (Land land : bucket) {
			if (x < land.minX() || x > land.maxX() || z < land.minZ() || z > land.maxZ()) continue;
			if (pointInPolygon(x, z, land.xs(), land.zs())) return land.name();
		}
		return null;
	}

	/**
	 * 最近港口判定：保持全表线性扫描。
	 *
	 * <p>港口<b>没有</b>做 chunk 索引，这是有意的取舍：
	 * <ul>
	 *   <li>港口表只有约 895 条，每条是 6 个整数运算，整表扫完约几微秒；</li>
	 *   <li>领地表有约 4274 条且每条要做 O(顶点数) 的射线法多边形判定，
	 *       那才是热点；</li>
	 *   <li>港口索引的正确性很难保证——「桶里没有」不等于「附近没有」
	 *       （登记半径是有限值），强行做索引要么引入与全表扫描不一致的结果，
	 *       要么退化成环形扩大搜索，代码量远大于收益。</li>
	 * </ul>
	 * 实测导向：瓶颈在哪就优化哪，不为优化而优化。
	 */
	public static NearestGateway nearestGatewayIndexed(int x, int z, List<Gateway> gateways) {
		return nearestGatewayIn(gateways, "w", "w", x, z);
	}

	/**
	 * 最近港口/驿站（平面距离），距离取整格数。
	 * 未加载/世界不匹配返回 null。
	 */
	public NearestGateway nearestGateway(String worldId, int x, int z) {
		String worldKey = worldId == null ? "" : worldId.replace(':', '_');
		if (!worldKey.equals(loadedWorld)) return null;
		// 港口表小且没有索引（见 nearestGatewayIndexed 的注释）
		return nearestGatewayIn(gateways, loadedWorld, worldKey, x, z);
	}

	/** 便捷格式："名称 (123m)" 或 null */
	public String nearestGatewayText(String worldId, int x, int z) {
		NearestGateway gw = nearestGateway(worldId, x, z);
		return gw == null ? null : gw.name() + " (" + gw.distance() + "m)";
	}

	/** 纯函数版最近港口判定（便于测试） */
	public static NearestGateway nearestGatewayIn(List<Gateway> gateways, String loadedWorld, String worldKey, int x, int z) {
		if (gateways.isEmpty() || !worldKey.equals(loadedWorld)) return null;
		Gateway best = null;
		long bestSq = Long.MAX_VALUE;
		for (Gateway gw : gateways) {
			long dx = gw.x() - (long) x;
			long dz = gw.z() - (long) z;
			long sq = dx * dx + dz * dz;
			if (sq < bestSq) {
				bestSq = sq;
				best = gw;
			}
		}
		return best == null ? null : new NearestGateway(best.name(), (int) Math.round(Math.sqrt(bestSq)));
	}

	/** 射线法（偶奇规则） */
	private static boolean pointInPolygon(int x, int z, int[] xs, int[] zs) {
		boolean inside = false;
		int n = xs.length;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			boolean intersect = (zs[i] > z) != (zs[j] > z)
					&& x < (xs[j] - xs[i]) * (double) (z - zs[i]) / (zs[j] - zs[i]) + xs[i];
			if (intersect) inside = !inside;
		}
		return inside;
	}

	public int loadedCount() {
		return lands.size();
	}
}
