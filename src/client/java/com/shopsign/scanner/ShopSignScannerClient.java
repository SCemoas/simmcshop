package com.shopsign.scanner;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShopSignScannerClient implements ClientModInitializer {
	/** 窗口动画时间轴：渐入 → 保持 → 渐出（之后隐藏） */
	private static final long FADE_IN_MS = 250;
	private static final long HOLD_MS = 5000;
	private static final long FADE_OUT_MS = 750;
	/** 手动扫描（K 键/命令）冷却：10 秒 */
	private static final long MANUAL_SCAN_COOLDOWN_MS = 10_000;
	/** 打字机速度：字符/毫秒 */
	private static final float CHARS_PER_MS = 0.045f;
	/** 位置行（领地/港口）相对摘要行的延迟 */
	private static final long LOCATION_REVEAL_DELAY_MS = 400;

	/** 窗口位置：左上角下移两行（约 20px），给 FPS 显示留出空间 */
	private static final int WIN_X = 8;
	private static final int WIN_Y = 28;

	private ModConfig config;
	private SignScanner scanner;
	private DatabaseReporter dbReporter;
	private LandResolver landResolver;
	private KeyMapping scanKey;
	private KeyMapping browseKey;
	private int tickCounter;
	private String lastSummary;
	private String lastLocation;
	private long windowUpdateTime;
	/** 已记录过的扫描错误（去重，避免日志刷屏） */
	private final Set<String> loggedScanErrors = new HashSet<>();
	/** 上次手动扫描时间（限速冷却用） */
	private long lastManualScanMs;

	/**
	 * mod 版本号，从 fabric.mod.json 读取。
	 *
	 * 不要在 HUD 里硬编码版本字符串 —— 之前写死成 "v1.0"，
	 * 一路发布到 2.2.0 都没人发现（显示的是错误版本）。
	 *
	 * <p>这里从 fabric.mod.json 读，不再硬编码 —— 2.2.1 曾把 "2.2.1"
	 * 直接写进源码，与 gradle.properties 的 version 各改一处就会不一致。
	 */
	static final String MOD_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer("shopsignscanner")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("?");

	// HUD 窗口渲染缓存：字符串构建 + 逐字符换行测量是每帧开销，内容未变时直接复用
	private List<FlatLine> cachedFlat;
	private int cachedFlatWidth = -1;
	private int cachedTotal = -1;
	private String cachedDb;
	private String cachedSummary;
	private String cachedLocation;

	@Override
	public void onInitializeClient() {
		config = ModConfig.load();

		// 2.2.3：按 uploadMode 选 reporter。api 模式上传走 HTTPS POST 到站点 ingest
		// 接口，客户端只持 apiBaseUrl + apiSecret，不碰数据库；
		// direct 模式是原有的 MySQL 直连（应急回退 / 对照测试）。
		// 两种模式共用同一份配置对象，DatabaseReporter 内部按 uploadMode 决定
		// Deliverer 实现，因此这里的构造调用不变。
		dbReporter = new DatabaseReporter(config, this::onDbEntriesLoaded);
		landResolver = new LandResolver(config);
		scanner = new SignScanner(config, landResolver, this::onNewRecord, dbReporter::markSeen);
		// 领地数据每次刷新后清扫描缓存，让 land/gateway 列随最新数据重算
		landResolver.setOnRefresh(scanner::clearTextCache);

		// 按键分类只能注册一次：两个键共用同一个 Category（重复 register 会抛
		// IllegalArgumentException 导致游戏启动崩溃，见 2026-09-26 错误报告）
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.parse("shopsignscanner:shopsignscanner"));
		scanKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.shopsignscanner.scan",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				category));

		browseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.shopsignscanner.browse",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				category));

		registerCommands();

		ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
		HudElementRegistry.addLast(
				Identifier.parse("shopsignscanner:terminal_window"),
				(guiGraphics, deltaTracker) -> renderTerminalWindow(guiGraphics));

		dbReporter.start();
		landResolver.start();
	}

	private void onDbEntriesLoaded(List<String[]> entries) {
		for (String[] entry : entries) {
			// 兼容两种长度：2 列 = 旧版只回读 raw，4 列 = 2.2.2 起带 land/gateway
			String land = entry.length > 2 ? entry[2] : "";
			String gateway = entry.length > 3 ? entry[3] : "";
			scanner.addSeenEntry(entry[0], entry[1], land, gateway);
		}
	}

	private void onNewRecord(ShopRecord record, boolean isUpdate) {
		dbReporter.submit(record);
		lastSummary = (isUpdate ? "[更新] " : "[新增] ") + summaryOf(record);
		lastLocation = locationOf(record);
		notifyWindowUpdate();
	}

	/** 数据更新时推动窗口动画时间轴：正在显示中则延长保持，已隐藏则重新渐入 */
	private void notifyWindowUpdate() {
		long now = System.currentTimeMillis();
		if (windowUpdateTime == 0 || now - windowUpdateTime >= FADE_IN_MS + HOLD_MS) {
			windowUpdateTime = now;
		} else {
			windowUpdateTime = Math.max(windowUpdateTime, now - FADE_IN_MS);
		}
	}

	/** 当前连接的服务器标识（与 SignScanner 内部一致），供 status 显示与白名单核对 */
	private static String currentServerName() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getSingleplayerServer() != null) return "singleplayer";
		var info = minecraft.getCurrentServer();
		return info != null && info.ip != null ? info.ip : "unknown";
	}

	private static String summaryOf(ShopRecord r) {		String typeText = switch (r.type()) {
			case ShopSignParser.TYPE_SELL -> "出售";
			case ShopSignParser.TYPE_BUY -> "收购";
			case ShopSignParser.TYPE_SOLD_OUT -> "缺货";
			case ShopSignParser.TYPE_NO_SPACE -> "满仓";
			default -> r.type();
		};
		String qty = r.quantity() == ShopSignParser.QUANTITY_INFINITE ? "（无限）"
				: r.quantity() > 0 ? " ×" + r.quantity() : "";
		return typeText + " " + r.item() + qty + " @ " + r.price() + "（" + r.owner() + "）";
	}

	/** 位置行：领地 + 最近港口/驿站（都为空则返回 null） */
	private static String locationOf(ShopRecord r) {
		List<String> parts = new ArrayList<>();
		if (r.land() != null && !r.land().isBlank()) parts.add("领地 " + r.land());
		if (r.gateway() != null && !r.gateway().isBlank()) parts.add("最近港口 " + r.gateway());
		return parts.isEmpty() ? null : String.join(" · ", parts);
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("shopsignscan")
						.then(ClientCommandManager.literal("scan").executes(context -> {
							tryManualScan(Minecraft.getInstance());
							return 1;
						}))
						.then(ClientCommandManager.literal("settings").executes(context -> {
							Minecraft.getInstance().setScreen(new SettingsScreen(this));
							return 1;
						}))
						.then(ClientCommandManager.literal("auto")
								.then(ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes(context -> {
									config.autoScan = BoolArgumentType.getBool(context, "enabled");
									config.save();
									context.getSource().sendFeedback(Component.literal(
											"自动捕捉已" + (config.autoScan ? "开启" : "关闭")));
									return 1;
								})))
						.then(ClientCommandManager.literal("radius")
								.then(ClientCommandManager.argument("blocks", IntegerArgumentType.integer(4, 64)).executes(context -> {
									config.radius = IntegerArgumentType.getInteger(context, "blocks");
									config.save();
									context.getSource().sendFeedback(Component.literal(
											"捕捉半径已设为 " + config.radius + " 格（4-64）"));
									return 1;
								})))
						.then(ClientCommandManager.literal("width")
								.then(ClientCommandManager.argument("px", IntegerArgumentType.integer(160, 500)).executes(context -> {
									config.panelWidth = IntegerArgumentType.getInteger(context, "px");
									config.save();
									context.getSource().sendFeedback(Component.literal(
											"终端窗口宽度已设为 " + config.panelWidth + "px（160-500）"));
									return 1;
								})))
						.then(ClientCommandManager.literal("notify")
								.then(ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes(context -> {
									config.notifyOverlay = BoolArgumentType.getBool(context, "enabled");
									config.save();
									context.getSource().sendFeedback(Component.literal(
											"左上角窗口已" + (config.notifyOverlay ? "开启" : "关闭")));
									return 1;
								})))
						.then(ClientCommandManager.literal("status").executes(context -> {
							String landInfo = config.landEnabled
									? landResolver.loadedCount() + "个领地已加载"
									: "已禁用";
							String allowlist = String.join("/", config.serverAllowlist);
							boolean currentAllowed = config.isServerAllowed(currentServerName());
							context.getSource().sendFeedback(Component.literal(String.format(
									"窗口=%s | 宽度=%dpx | 自动捕捉=%s | 频率=%d秒 | 半径=%d格 | 已记录=%d条 | 领地分表=%s | 数据库=%s | 上传节流=%d条/%d秒 | 允许服务器=%s（当前:%s）",
									config.showWindow ? "开" : "关",
									config.panelWidth,
									config.autoScan ? "开" : "关",
									config.intervalTicks / 20,
									config.radius,
									scanner.totalRecorded(),
									landInfo,
									dbReporter.statusText(),
									config.uploadBatchSize,
									config.uploadIntervalMs / 1000,
									allowlist,
									currentAllowed ? "允许" : "不允许")));
							return 1;
						}))));
	}

	private void onEndTick(Minecraft minecraft) {
		while (scanKey.consumeClick()) {
			tryManualScan(minecraft);
		}
		while (browseKey.consumeClick()) {
			minecraft.setScreen(new ShopBrowserScreen(this));
		}

		if (!config.autoScan) return;
		if (minecraft.level == null || minecraft.player == null) {
			tickCounter = 0;
			return;
		}
		// 2.2.2：一轮完整扫描被拆成多个 tick 推进（SignScanner.tickScan），
		// 这里不再是「每 intervalTicks 触发一次全量扫描」，
		// 而是每 intervalTicks 结算一次覆盖进度：
		// scanInProgress() 为真说明上一轮还没扫完，这一 tick 继续推进；
		// 上一轮已完成则开始新一轮。单 tick 成本与扫描半径无关。
		if (++tickCounter >= config.intervalTicks) {
			tickCounter = 0;
			safeTickScan(minecraft);
		}
	}

	/** 与 {@link #safeScan} 相同的心智，但推进的是分帧扫描 */
	private void safeTickScan(Minecraft minecraft) {
		try {
			int added = scanner.tickScan(minecraft);
			if (added > 0 && minecraft.player != null) {
				minecraft.player.displayClientMessage(Component.literal(
						"§7[shopsignscanner] 本轮扫描完成，新增记录 " + added + " 条"), true);
			}
		} catch (Exception e) {
			String key = e.getClass().getName() + ": " + e.getMessage();
			if (loggedScanErrors.add(key)) {
				System.err.println("[shopsignscanner] 扫描出错（同类错误只记一次，采集继续）: ");
				e.printStackTrace();
			}
		}
	}

	/** 手动扫描限速：10 秒冷却，触发时提示 */
	private void tryManualScan(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (now - lastManualScanMs < MANUAL_SCAN_COOLDOWN_MS) {
			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(Component.literal(
						"§7[shopsignscanner] 扫描太频繁，请" + ((MANUAL_SCAN_COOLDOWN_MS - (now - lastManualScanMs)) / 1000 + 1) + "秒后再试"), true);
			}
			return;
		}
		lastManualScanMs = now;
		safeScan(minecraft);
	}

	/**
	 * 扫描永不把游戏带崩：玩家自创的牌面内容可能触发解析器的意外路径
	 * （如 2026-09-26 的「出售」无数量 NPE），这里兜底捕获、同类错误只记一次日志。
	 */
	private void safeScan(Minecraft minecraft) {
		try {
			scanner.scan(minecraft);
		} catch (Exception e) {
			String key = e.getClass().getName() + ": " + e.getMessage();
			if (loggedScanErrors.add(key)) {
				System.err.println("[shopsignscanner] 扫描出错（同类错误只记一次，采集继续）: ");
				e.printStackTrace();
			}
		}
	}

	/** 一行待渲染内容：text/color/revealStartMs（<0 表示不参与打字机动画） */
	private record RenderLine(String text, int color, long revealStartMs) {
	}

	/** 扁平化后的一行：换行后的子行 + 所属动画块的起始可见字符偏移 */
	private record FlatLine(String text, int color, long revealStartMs, int charOffset) {
	}

	/**
	 * 左上角 shopsignscanner 窗口。仅在数据更新后显示（渐入→保持→渐出）。
	 * 文本按面板宽度自动换行，超长不出框；摘要与位置行带打字机动画；
	 * 面板宽度可在设置/命令中调整（config.panelWidth），高度按内容自适应并受屏幕约束。
	 *
	 * <p><b>性能</b>：本方法每帧调用。字符串拼接与逐字符换行测量（CJK 逐码点测宽）
	 * 都不便宜，内容未变时直接复用上一次的扁平化结果，只在 累计数/摘要/位置/数据库状态
	 * /面板宽度 任一变化时重建。
	 */
	private void renderTerminalWindow(GuiGraphics guiGraphics) {
		if (!config.showWindow || !config.notifyOverlay) return;
		if (windowUpdateTime == 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options.hideGui || minecraft.level == null) return;

		String dbStatus = dbReporter.statusText();
		boolean dirty = cachedFlat == null || cachedFlatWidth != config.panelWidth
				|| cachedTotal != scanner.totalRecorded()
				|| !java.util.Objects.equals(cachedDb, dbStatus)
				|| !java.util.Objects.equals(cachedSummary, lastSummary)
				|| !java.util.Objects.equals(cachedLocation, lastLocation);
		if (dirty) {
			cachedTotal = scanner.totalRecorded();
			cachedDb = dbStatus;
			cachedSummary = lastSummary;
			cachedLocation = lastLocation;
			// ⚠️ 必须先更新 cachedFlatWidth 再重建：rebuild 用它算换行宽度。
			// 之前赋值顺序反了，首次构建时宽度还是 -1，换行宽变成负数 →
			// 每个字符单独一行，窗口显示成一列竖排单字（首次收录必现）。
			cachedFlatWidth = config.panelWidth;
			cachedFlat = rebuildWindowLines(minecraft.font, dbStatus);
		}

		long now = System.currentTimeMillis();
		long elapsed = now - windowUpdateTime;
		float alpha;
		if (elapsed < 0) return;
		if (elapsed < FADE_IN_MS) {
			alpha = (float) elapsed / FADE_IN_MS;
		} else if (elapsed < FADE_IN_MS + HOLD_MS) {
			alpha = 1f;
		} else if (elapsed < FADE_IN_MS + HOLD_MS + FADE_OUT_MS) {
			alpha = 1f - (float) (elapsed - FADE_IN_MS - HOLD_MS) / FADE_OUT_MS;
		} else {
			return;
		}
		if (alpha <= 0f) return;

		Font font = minecraft.font;
		int x = WIN_X;
		int y = WIN_Y;
		int w = config.panelWidth;
		int lineH = font.lineHeight + 1;
		int maxLines = Math.max(3, (guiGraphics.guiHeight() - y - 8) / lineH);

		List<FlatLine> flat = cachedFlat.size() > maxLines ? cachedFlat.subList(0, maxLines) : cachedFlat;

		int h = flat.size() * lineH + 6;

		// 背景与边框随 alpha 淡化
		guiGraphics.fill(x, y, x + w, y + h, (int) (0xB0 * alpha) << 24);
		int border = applyAlpha(0x00A8FF, alpha);
		guiGraphics.fill(x, y, x + w, y + 1, border);
		guiGraphics.fill(x, y + h - 1, x + w, y + h, border);

		// 逐行绘制（打字机动画按可见字符数截断）
		for (int i = 0; i < flat.size(); i++) {
			FlatLine line = flat.get(i);
			String text = line.text();
			if (line.revealStartMs() >= 0) {
				long since = now - line.revealStartMs();
				int visible = (int) (since * CHARS_PER_MS) - line.charOffset();
				if (visible <= 0) continue;
				if (visible < text.length()) text = text.substring(0, visible);
			}
			guiGraphics.drawString(font, text, x + 5, y + 2 + i * lineH, applyAlpha(line.color() & 0xFFFFFF, alpha), true);
		}
		// ⚠️ 禁止在此（HudElementRegistry 的 HUD 回调）调用 renderItem：
		// 1.21.11 新 GUI 管线下会触发 GPU 驱动死锁，玩家整机会死机（2026-09-26 事故，已回退）
	}

	/** 构建窗口行并扁平化（只在内容变化时调用） */
	private List<FlatLine> rebuildWindowLines(Font font, String dbStatus) {
		List<RenderLine> lines = new ArrayList<>();
		lines.add(new RenderLine("shopsignscanner · v" + MOD_VERSION, 0x00BFFF, -1));
		lines.add(new RenderLine("累计收录 " + cachedTotal + " 条", 0xFFFFFF, -1));
		if (cachedSummary != null) {
			lines.add(new RenderLine("最近: " + cachedSummary, 0xFFD700, windowUpdateTime));
		}
		if (cachedLocation != null) {
			lines.add(new RenderLine("位置: " + cachedLocation, 0x9AD0FF, windowUpdateTime + LOCATION_REVEAL_DELAY_MS));
		}
		lines.add(new RenderLine("感谢您的使用与贡献", 0xAAAAAA, -1));
		lines.add(new RenderLine(dbStatus, dbStatusColor(dbStatus), -1));

		int maxTextW = cachedFlatWidth - 10;
		List<FlatLine> flat = new ArrayList<>();
		for (RenderLine line : lines) {
			List<String> wrapped = wrap(font, line.text(), maxTextW);
			int offset = 0;
			for (String part : wrapped) {
				flat.add(new FlatLine(part, line.color(), line.revealStartMs(), offset));
				offset += part.length();
			}
		}
		return flat;
	}

	/** 按像素宽度贪心换行（逐字符测量，CJK 友好；换行后跳过行首空格）。宽度非法时不换行 */
	private static List<String> wrap(Font font, String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			out.add("");
			return out;
		}
		if (maxWidth <= 0) {
			out.add(text);
			return out;
		}
		StringBuilder cur = new StringBuilder();
		int curW = 0;
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			int cw = font.width(ch);
			if (curW + cw > maxWidth && cur.length() > 0) {
				out.add(cur.toString());
				cur.setLength(0);
				curW = 0;
				if (" ".equals(ch)) {
					i += ch.length();
					continue;
				}
			}
			cur.append(ch);
			curW += cw;
			i += ch.length();
		}
		out.add(cur.toString());
		return out;
	}

	private int dbStatusColor(String s) {
		// api 模式的状态行没有「已连接/未连接」可言（每批都是独立 HTTPS 请求），
		// 命中「API 上报」即视为在线（绿），失败分支以红色兜底。
		if (s.contains("API 上报")) return s.contains("失败") ? 0xFF5555 : 0x55FF55;
		if (s.contains("已连接")) return 0x55FF55;
		if (s.contains("待上传")) return 0xFFFF55;
		if (s.contains("已禁用")) return 0x888888;
		return 0xFF5555;
	}

	private static int applyAlpha(int rgb, float alpha) {
		int r = (int) (((rgb >> 16) & 0xFF) * alpha);
		int g = (int) (((rgb >> 8) & 0xFF) * alpha);
		int b = (int) ((rgb & 0xFF) * alpha);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	ModConfig getConfig() {
		return config;
	}

	KeyMapping getScanKey() {
		return scanKey;
	}

	KeyMapping getBrowseKey() {
		return browseKey;
	}

	DatabaseReporter getDbReporter() {
		return dbReporter;
	}
}
