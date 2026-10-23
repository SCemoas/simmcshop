package com.shopsign.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模组配置，保存在 config/shopsignscanner.json。
 * 2.2.0 起本地 txt 输出已移除，配置中不再有文件输出路径相关项。
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final String[] DEFAULT_SELL = {"出售", "販售", "贩售", "售卖", "賣出", "卖出", "selling", "sell"};
	private static final String[] DEFAULT_BUY = {"收购", "收購", "购买", "購買", "买入", "買入", "buying", "buy"};
	private static final String[] DEFAULT_SOLD_OUT = {"缺货", "缺貨", "售罄", "已售完", "sold out", "out of stock"};
	private static final String[] DEFAULT_NO_SPACE = {"空间不足", "空間不足", "容量不足", "已满", "满仓", "滿倉", "no space", "not enough space", "inventory full"};
	private static final String[] DEFAULT_PRICE = {"单价", "單價", "价格", "價格", "price"};
	private static final String[] DEFAULT_INFINITE = {"无限", "無限", "infinite", "unlimited"};
	/** 默认放行全部服务器；建议按需改成自己的服务器主机名 */
	private static final String[] DEFAULT_ALLOWLIST = {"*"};

	public int radius = 32;
	/**
	 * 自动捕捉间隔（tick）。20 tick = 1 秒。
	 *
	 * <p>声明值必须与 {@link #sanitize()} 的下限一致：之前这里是 100 而下限写 200，
	 * 玩家加载配置时先拿到 100，保存时被钳成 200 —— 同一次会话里改半径会连带
	 * 把频率改掉，且 UI 上的档位是 10/30/60 秒，100 tick（5 秒）根本不在档位里。
	 */
	public int intervalTicks = 200;
	public boolean autoScan = true;
	public boolean strictName = false;
	public boolean oakOnly = true;
	public boolean notifyOverlay = true;
	public boolean showWindow = true;
	public boolean dbEnabled = true;
	/** direct 模式的数据库主机；留空表示不启用直连 */
	public String dbHost = "";
	public int dbPort = 3306;
	public String dbDatabase = "shop";
	public String dbUser = "";
	/**
	 * 直连 MySQL 的口令。2.2.3 起默认值改为空串：默认上传模式已切成 api（HTTPS POST），
	 * 口令不再随客户端分发；只有显式把 uploadMode 改成 direct 才需要填它。
	 */
	public String dbPassword = "";
	/**
	 * 上传模式：{@code api}（默认）/ {@code direct}。
	 *
	 * <ul>
	 *   <li>{@code api}：上传走 HTTPS POST 到商店网站的 ingest 接口，
	 *       只需要 apiBaseUrl + apiSecret，<b>不碰数据库</b>；</li>
	 *   <li>{@code direct}：2.2.3 之前的 MySQL 直连行为，留作对照测试与应急回退。</li>
	 * </ul>
	 */
	public String uploadMode = "api";
	/** 上报后端根地址（末尾斜杠会被去掉）；留空则拒绝上报 */
	public String apiBaseUrl = "";
	/** 与站点 INGEST_SHARED_SECRET 一致的共享密钥；api 模式下必填 */
	public String apiSecret = "";
	public boolean landEnabled = true;
	/** Squaremap 地图站根地址；留空则跳过领地/港口判定 */
	public String mapBaseUrl = "";
	public String mapWorld = "minecraft_overworld";
	public int landRefreshMinutes = 30;
	public int panelWidth = 250;
	/** 服务器白名单：仅这些服务器会被采集（精确匹配主机名，去端口，忽略大小写；"*" 放行全部） */
	public String[] serverAllowlist = DEFAULT_ALLOWLIST;
	/** 上传节流：每 uploadIntervalMs 毫秒最多 uploadBatchSize 条（补传同样受限） */
	public int uploadBatchSize = 10;
	public int uploadIntervalMs = 5000;
	/** 领地黑名单：这些领地的商店不采集、不入库（精确去空格、忽略大小写匹配） */
	public List<String> landBlacklist = new ArrayList<>();
	public String[] sellKeywords = DEFAULT_SELL;
	public String[] buyKeywords = DEFAULT_BUY;
	public String[] soldOutKeywords = DEFAULT_SOLD_OUT;
	public String[] noSpaceKeywords = DEFAULT_NO_SPACE;
	public String[] priceKeywords = DEFAULT_PRICE;
	public String[] infiniteKeywords = DEFAULT_INFINITE;

	public static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("shopsignscanner.json");
		try {
			if (Files.exists(path)) {
				ModConfig config = GSON.fromJson(Files.readString(path), ModConfig.class);
				if (config != null) {
					config.sanitize();
					return config;
				}
			}
		} catch (Exception e) {
			System.err.println("[ShopSignScanner] 读取配置失败，使用默认配置: " + e);
		}
		ModConfig config = new ModConfig();
		config.save();
		return config;
	}

	public void save() {
		sanitize();
		try {
			Path path = FabricLoader.getInstance().getConfigDir().resolve("shopsignscanner.json");
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			System.err.println("[ShopSignScanner] 保存配置失败: " + e);
		}
	}

	public void sanitize() {
		if (radius < 4) radius = 4;
		if (radius > 64) radius = 64;
		// 抓取速率下限：最快 10 秒一次（保守限速，200 tick）
		if (intervalTicks < 200) intervalTicks = 200;
		if (landRefreshMinutes < 5) landRefreshMinutes = 5;
		if (mapBaseUrl == null) mapBaseUrl = "";
		if (mapWorld == null || mapWorld.isBlank()) mapWorld = "minecraft_overworld";
		if (panelWidth < 160) panelWidth = 160;
		if (panelWidth > 500) panelWidth = 500;
		if (uploadBatchSize < 1) uploadBatchSize = 1;
		if (uploadBatchSize > 1000) uploadBatchSize = 1000;
		if (uploadIntervalMs < 1000) uploadIntervalMs = 1000;
		if (uploadIntervalMs > 60000) uploadIntervalMs = 60000;
		if (landBlacklist == null) landBlacklist = new ArrayList<>();
		landBlacklist.removeIf(s -> s == null || s.isBlank());
		for (int i = 0; i < landBlacklist.size(); i++) {
			landBlacklist.set(i, landBlacklist.get(i).trim());
		}
		serverAllowlist = normalize(serverAllowlist, DEFAULT_ALLOWLIST);
		sellKeywords = normalize(sellKeywords, DEFAULT_SELL);
		buyKeywords = normalize(buyKeywords, DEFAULT_BUY);
		soldOutKeywords = normalize(soldOutKeywords, DEFAULT_SOLD_OUT);
		noSpaceKeywords = normalize(noSpaceKeywords, DEFAULT_NO_SPACE);
		priceKeywords = normalize(priceKeywords, DEFAULT_PRICE);
		infiniteKeywords = normalize(infiniteKeywords, DEFAULT_INFINITE);
		// 上传模式：只认 api / direct。手写错（大小写、别名、空值）时归一到 api ——
		// 那是 2.2.3 起的默认模式，也是唯一不需要数据库口令的模式，
		// 归一到 direct 会让没填口令的用户静默失去上传能力。
		if (uploadMode == null) {
			uploadMode = "api";
		} else {
			uploadMode = uploadMode.trim().toLowerCase(Locale.ROOT);
			if (!uploadMode.equals("api") && !uploadMode.equals("direct")) {
				System.err.println("[ShopSignScanner] uploadMode 取值无效（" + uploadMode
						+ "），已归一为 api；可用值：api / direct");
				uploadMode = "api";
			}
		}
		if (apiBaseUrl == null) {
			apiBaseUrl = "";
		} else {
			apiBaseUrl = apiBaseUrl.trim();
			// 去尾斜杠：ApiClient 拼路径时会自己再去一次，但这里先归一能让
			// 配置保存后就是规范形式，玩家看到的也是。
			while (apiBaseUrl.endsWith("/")) {
				apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
			}
		}
		if (apiSecret == null) apiSecret = "";
		else apiSecret = apiSecret.trim();
	}

	/** 是否 API 上报模式（上传走 HTTPS POST，不看 dbEnabled） */
	public boolean isApiMode() {
		return "api".equals(uploadMode);
	}

	private static String[] normalize(String[] value, String[] fallback) {
		if (value == null) return fallback.clone();
		int nonBlank = 0;
		for (String s : value) {
			if (s != null && !s.isBlank()) nonBlank++;
		}
		if (nonBlank == 0) return fallback.clone();
		String[] result = new String[nonBlank];
		int i = 0;
		for (String s : value) {
			if (s != null && !s.isBlank()) result[i++] = s.trim();
		}
		return result;
	}

	/**
	 * 服务器白名单判定。server 来自客户端当前连接（如 "play.example.com"、"mc.x.cn:25565"、
	 * "singleplayer"）。匹配规则：取主机名（去端口）、忽略大小写，与白名单**精确匹配**；
	 * 白名单里写 "*" 可放行全部（默认值即 "*"）。
	 */
	public boolean isServerAllowed(String server) {
		if (server == null || server.isBlank()) return false;
		String host = normalizeHost(server);
		if (serverAllowlist == null) return false;
		for (String entry : serverAllowlist) {
			if (entry == null || entry.isBlank()) continue;
			String trimmed = entry.trim();
			if ("*".equals(trimmed)) return true;
			String allowed = normalizeHost(trimmed);
			if (!allowed.isEmpty() && allowed.equals(host)) return true;
		}
		return false;
	}

	private static String normalizeHost(String server) {
		String host = server.trim().toLowerCase(Locale.ROOT);
		int colon = host.indexOf(':');
		if (colon > 0) host = host.substring(0, colon);
		return host;
	}

	/** 领地黑名单判定：去空格、忽略大小写精确匹配 */
	public boolean isLandBlacklisted(String land) {
		if (land == null || land.isBlank() || landBlacklist == null || landBlacklist.isEmpty()) return false;
		String key = land.trim();
		for (String entry : landBlacklist) {
			if (entry != null && entry.equalsIgnoreCase(key)) return true;
		}
		return false;
	}
}
