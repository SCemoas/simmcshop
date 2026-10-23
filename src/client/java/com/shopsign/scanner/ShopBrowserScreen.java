package com.shopsign.scanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 商店查询面板：居中悬浮窗口，数据源为 **MySQL 数据库**（2.2.0 起本地 txt 已移除）。
 * 异步读取（数据库工作线程查询，完成后回主线程应用），需要数据库已连接。
 *
 * <p>两级视图：默认为**整合视图**——同类物品的商店合并为一行（显示售/收价格区间），
 * 点击进入该物品的商店列表：出售价从低到高、收购价从高到低，可「返回」。
 * 默认只显示出售（可切换收购/全部）；满仓/缺货默认隐藏，可勾选显示；
 * 黑名单领地的商店不显示。
 */
public class ShopBrowserScreen extends Screen {
	/** 类型筛选：默认出售 → 收购 → 全部 */
	private static final String[] FILTER_NAMES = {"出售", "收购", "全部"};
	private static final String[] FILTER_TYPES = {ShopSignParser.TYPE_SELL, ShopSignParser.TYPE_BUY, null};
	/** 类型标签颜色：未知/出售/收购/满仓/缺货 */
	private static final int[] TYPE_COLORS = {0xCCCCCC, 0x55FF55, 0xFFD700, 0xFFA500, 0xFF5555};

	private static int opaque(int rgb) {
		return 0xFF000000 | rgb;
	}

	private static final int COL_BG = opaque(0x0D141B);
	private static final int COL_BORDER = opaque(0x2E6E8E);
	private static final int COL_HEADER = 0xF010202C;
	private static final int COL_TITLE = opaque(0x66D9FF);
	private static final int COL_ZEBRA = 0x12FFFFFF;
	private static final int COL_HOVER = 0x22FFFFFF;
	private static final int COL_SELECTED = 0x3866D9FF;
	private static final int COL_ROW = opaque(0xD8D8D8);
	private static final int COL_META = opaque(0x93A4B3);
	private static final int COL_PRICE = opaque(0xFFD700);
	private static final int COL_WHITE = opaque(0xFFFFFF);
	private static final int COL_INPUT = opaque(0x0A121A);

	private static final int ROW_HEIGHT = 22;
	private static final int PAD = 6;
	private static final long COPIED_FEEDBACK_MS = 1500;

	private final ShopSignScannerClient client;

	private EditBox searchBox;
	/** 数据库异步读取结果（工作线程构建完整列表后整体替换，渲染线程只读） */
	private volatile List<ShopRecord> allRecords = List.of();
	private volatile boolean loading;
	/** 经类型/满仓缺货/搜索/黑名单过滤后的商店 */
	private List<ShopRecord> filtered = List.of();
	/** 整合视图的分组（按物品名，Collator 中文序） */
	private List<Group> groups = List.of();

	private int typeFilterIndex;
	private boolean showSoldStates;
	private Mode mode = Mode.GROUPS;
	private String currentGroupItem;
	private List<ShopRecord> currentGroupShops = List.of();

	private int scrollOffset;
	private int selectedIndex = -1;
	private int hoveredIndex = -1;
	private String loadError;

	private enum Mode {
		GROUPS, SHOPS
	}

	/** 整合视图的一行：同物品的所有商店 */
	private static class Group {
		final String item;
		final List<ShopRecord> shops;

		Group(String item, List<ShopRecord> shops) {
			this.item = item;
			this.shops = shops;
		}

		Double min(boolean sell) {
			return priceBound(sell, true);
		}

		Double max(boolean sell) {
			return priceBound(sell, false);
		}

		private Double priceBound(boolean sell, boolean min) {
			Double best = null;
			for (ShopRecord r : shops) {
				boolean isSell = ShopSignParser.TYPE_SELL.equals(r.type());
				if (sell != isSell) continue;
				double p = r.price();
				if (best == null || (min ? p < best : p > best)) best = p;
			}
			return best;
		}
	}

	// 面板几何
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listTop;
	private int listBottom;
	private int footerTop;

	private record Rect(int x, int y, int w, int h) {
		boolean contains(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}

	private Rect btnFilter;
	private Rect btnSold;
	private Rect btnRefresh;
	private Rect btnBack;
	private Rect btnCopyPos;
	private Rect btnCopyOwner;
	private Rect btnCopyGateway;
	private double hoverX = -1;
	private double hoverY = -1;
	private String copiedLabel;
	private long copiedUntil;

	public ShopBrowserScreen(ShopSignScannerClient client) {
		super(Component.literal("shopsignscanner · 商店查询"));
		this.client = client;
	}

	@Override
	protected void init() {
		panelW = clamp((int) (this.width * 0.72f), 280, 470);
		panelH = clamp((int) (this.height * 0.78f), 200, 270);
		panelX = (this.width - panelW) / 2;
		panelY = (this.height - panelH) / 2;

		footerTop = panelY + panelH - 46;
		listTop = panelY + 40;
		listBottom = footerTop - 3;

		reload();

		int controlY = panelY + 21;
		int controlH = 15;
		int filterW = 44;
		int soldW = 66;
		int refreshW = 36;
		int searchW = panelW - PAD * 2 - filterW - soldW - refreshW - 12;

		// ⚠️ 不能用 addRenderableWidget：super.render 阶段它会画在面板背景之下，
		// 被面板填充盖住（键入文字"看不见"的原因）。改为 addWidget（只参与输入），
		// 渲染由本类在面板之上手动调用。
		searchBox = new EditBox(this.font, panelX + PAD + 1, controlY + 2, searchW - 2, controlH - 4, Component.literal("搜索"));
		searchBox.setValue("");
		searchBox.setMaxLength(64);
		searchBox.setHint(Component.literal("搜索 物品 / 店主 / 领地 / 港口 / 坐标 / 服务器…"));
		searchBox.setBordered(false);
		searchBox.setTextColor(COL_TITLE);
		searchBox.setResponder(s -> {
			refilter();
			scrollOffset = 0;
			selectedIndex = -1;
		});
		addWidget(searchBox);
		setInitialFocus(searchBox);

		btnFilter = new Rect(panelX + PAD + searchW + 4, controlY, filterW, controlH);
		btnSold = new Rect(btnFilter.x + filterW + 3, controlY, soldW, controlH);
		btnRefresh = new Rect(panelX + panelW - PAD - refreshW, controlY, refreshW, controlH);
		layoutCopyButtons();
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private void layoutCopyButtons() {
		int w = 46;
		int h = 11;
		int gap = 4;
		int right = panelX + panelW - PAD - 2;
		btnCopyGateway = new Rect(right - w, footerTop + 30, w, h);
		btnCopyOwner = new Rect(btnCopyGateway.x - gap - w, footerTop + 30, w, h);
		btnCopyPos = new Rect(btnCopyOwner.x - gap - w, footerTop + 30, w, h);
		btnBack = new Rect(panelX + panelW - PAD - 40, panelY + 3, 40, 12);
	}

	private String typeFilterLabel() {
		return "类型: " + FILTER_NAMES[typeFilterIndex];
	}

	private String soldToggleLabel() {
		return showSoldStates ? "满仓/缺货: 显示" : "满仓/缺货: 隐藏";
	}

	/** 重新读取本地 txt：每个位置键只保留最后一条（最新状态） */
	private void reload() {
		if (loading) return;
		loadError = null;
		if (!client.getConfig().dbEnabled) {
			loadError = "数据库已禁用（config 里 dbEnabled=false），面板无数据源。";
			return;
		}
		loading = true;
		client.getDbReporter().fetchShopsAsync((records, error) ->
				Minecraft.getInstance().execute(() -> {
					loading = false;
					if (error != null) {
						loadError = "读取数据库失败：" + error;
						return;
					}
					applyLoaded(records);
				}));
	}

	/** 主线程应用读取结果：按位置键去重（保留最新）、排序、重建分组 */
	private void applyLoaded(List<ShopRecord> records) {
		Map<String, ShopRecord> latest = new HashMap<>();
		for (ShopRecord r : records) {
			latest.put(r.locationKeyMd5(), r);
		}
		allRecords = new ArrayList<>(latest.values());
		allRecords.sort(Comparator.comparing((ShopRecord r) -> r.time()).reversed());
		refilter();
		scrollOffset = 0;
		selectedIndex = -1;
	}

	private void refilter() {
		String q = normalizeQuery(searchBox == null ? "" : searchBox.getValue());
		String type = FILTER_TYPES[typeFilterIndex];
		List<ShopRecord> out = new ArrayList<>();
		for (ShopRecord r : allRecords) {
			if (type != null && !type.equals(r.type())) continue;
			if (!showSoldStates && isSoldState(r)) continue;
			// 黑名单领地的历史数据不在面板展示（新数据已由采集端拦截）
			if (client.getConfig().isLandBlacklisted(r.land())) continue;
			if (!q.isEmpty() && !matches(r, q)) continue;
			out.add(r);
		}
		filtered = out;

		// 按物品分组（Collator 中文拼音序）
		Map<String, List<ShopRecord>> byItem = new HashMap<>();
		for (ShopRecord r : filtered) {
			byItem.computeIfAbsent(r.item(), k -> new ArrayList<>()).add(r);
		}
		Collator collator = Collator.getInstance(Locale.CHINA);
		groups = new ArrayList<>();
		for (Map.Entry<String, List<ShopRecord>> e : byItem.entrySet()) {
			groups.add(new Group(e.getKey(), e.getValue()));
		}
		groups.sort((a, b) -> collator.compare(a.item, b.item));
	}

	private static boolean isSoldState(ShopRecord r) {
		return ShopSignParser.TYPE_SOLD_OUT.equals(r.type()) || ShopSignParser.TYPE_NO_SPACE.equals(r.type());
	}

	/** 商店排序：出售价低→高，收购价高→低，满仓/缺货垫底（按时间倒序） */
	private static void sortShops(List<ShopRecord> shops) {
		shops.sort((a, b) -> {
			int ra = rank(a);
			int rb = rank(b);
			if (ra != rb) return ra - rb;
			if (ra == 0) {
				int byPrice = Double.compare(a.price(), b.price());
				return byPrice != 0 ? byPrice : b.time().compareTo(a.time());
			}
			if (ra == 1) {
				int byPrice = Double.compare(b.price(), a.price());
				return byPrice != 0 ? byPrice : b.time().compareTo(a.time());
			}
			return b.time().compareTo(a.time());
		});
	}

	private static int rank(ShopRecord r) {
		if (ShopSignParser.TYPE_SELL.equals(r.type())) return 0;
		if (ShopSignParser.TYPE_BUY.equals(r.type())) return 1;
		return 2;
	}

	private static String normalizeQuery(String s) {
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('，', ',').replace(" ", "");
	}

	private boolean matches(ShopRecord r, String q) {
		return contains(r.item(), q) || contains(r.owner(), q) || contains(r.land(), q)
				|| contains(r.gateway(), q) || contains(r.server(), q) || contains(r.world(), q)
				|| coordString(r).contains(q);
	}

	private static String coordString(ShopRecord r) {
		return r.x() + "," + r.y() + "," + r.z();
	}

	private static boolean contains(String s, String q) {
		return s != null && !s.isEmpty() && s.toLowerCase(Locale.ROOT).contains(q);
	}

	/** 当前列表行数（两种视图都是两行一条） */
	private List<?> currentRows() {
		return mode == Mode.GROUPS ? groups : currentGroupShops;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		hoverX = mouseX;
		hoverY = mouseY;

		drawPanelFrame(g);

		// 顶部按钮
		drawFlatButton(g, btnFilter, typeFilterLabel(), false);
		drawFlatButton(g, btnSold, soldToggleLabel(), showSoldStates);
		drawFlatButton(g, btnRefresh, "刷新", false);

		g.drawString(this.font, "shopsignscanner · 商店查询", panelX + PAD + 2, panelY + 5, COL_TITLE, true);
		String stat = "共 " + allRecords.size() + " 家 · 显示 " + filtered.size() + " 家 · 数据库";
		g.drawString(this.font, stat, panelX + panelW - PAD - this.font.width(stat) - 18, panelY + 5, COL_META, true);

		// 搜索输入框：面板之上手动渲染（底色 + 边框 + 输入内容可见）
		g.fill(panelX + PAD, panelY + 20, panelX + PAD + searchBox.getWidth(), panelY + 37, COL_INPUT);
		g.fill(panelX + PAD, panelY + 37, panelX + PAD + searchBox.getWidth(), panelY + 38, COL_BORDER);
		searchBox.render(g, mouseX, mouseY, partialTick);

		if (loading) {
			g.drawString(this.font, "正在从数据库读取商店数据…", panelX + PAD, listTop + 6, COL_TITLE, true);
			return;
		}
		if (loadError != null) {
			g.drawString(this.font, truncate(loadError, panelW - PAD * 2 - 4), panelX + PAD, listTop + 6, opaque(0xFF5555), true);
			g.drawString(this.font, "连上数据库后，点上方「刷新」重试。", panelX + PAD, listTop + 18, COL_META, true);
			return;
		}

		// 整合视图下 SHOPS 模式头部返回按钮
		if (mode == Mode.SHOPS) {
			drawFlatButton(g, btnBack, "返回", false);
		}

		if (emptyListMessage(g)) return;

		List<?> rows = currentRows();
		int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int maxOffset = Math.max(0, rows.size() - visibleRows);
		if (scrollOffset > maxOffset) scrollOffset = maxOffset;
		if (scrollOffset < 0) scrollOffset = 0;
		hoveredIndex = -1;
		for (int i = 0; i < visibleRows; i++) {
			int idx = scrollOffset + i;
			if (idx >= rows.size()) break;
			int y = listTop + i * ROW_HEIGHT;
			if ((idx & 1) == 1) {
				g.fill(panelX + 2, y, panelX + panelW - 2, y + ROW_HEIGHT, COL_ZEBRA);
			}
			if (isMouseInList(mouseX, mouseY) && mouseY >= y && mouseY < y + ROW_HEIGHT) {
				hoveredIndex = idx;
				g.fill(panelX + 2, y, panelX + panelW - 2, y + ROW_HEIGHT, COL_HOVER);
			}
			if (mode == Mode.SHOPS && idx == selectedIndex) {
				g.fill(panelX + 2, y, panelX + panelW - 2, y + ROW_HEIGHT, COL_SELECTED);
			}
		}

		int maxW = panelW - PAD * 2 - 10;
		for (int i = 0; i < visibleRows; i++) {
			int idx = scrollOffset + i;
			if (idx >= rows.size()) break;
			int y = listTop + i * ROW_HEIGHT;
			if (mode == Mode.GROUPS) {
				drawGroupRow(g, (Group) rows.get(idx), panelX + PAD, y, maxW);
			} else {
				drawEntry(g, (ShopRecord) rows.get(idx), panelX + PAD, y, maxW, idx == selectedIndex);
			}
		}

		// 滚动条
		if (rows.size() > visibleRows) {
			int barX = panelX + panelW - 5;
			int trackTop = listTop + 1;
			int trackH = listBottom - listTop - 2;
			int barH = Math.max(10, trackH * visibleRows / rows.size());
			int travel = trackH - barH;
			int barY = trackTop + (maxOffset > 0 ? (int) ((long) scrollOffset * travel / maxOffset) : 0);
			g.fill(barX, trackTop, barX + 3, trackTop + trackH, 0x30FFFFFF);
			g.fill(barX, barY, barX + 3, barY + barH, COL_TITLE);
		}

		// 详情区
		g.fill(panelX + 2, footerTop, panelX + panelW - 2, panelY + panelH - 2, COL_HEADER);
		if (mode == Mode.SHOPS && selectedIndex >= 0 && selectedIndex < currentGroupShops.size()) {
			ShopRecord r = currentGroupShops.get(selectedIndex);
			int dy = footerTop + 3;
			g.drawString(this.font, truncate(detailLine1(r), panelW - PAD * 2 - 4), panelX + PAD, dy, COL_WHITE, true);
			g.drawString(this.font, truncate(detailLine2(r), panelW - PAD * 2 - 4), panelX + PAD, dy + 10, opaque(0xC8C8C8), true);
		} else if (mode == Mode.SHOPS) {
			g.drawString(this.font, "点击列表选中一家商店；下方按钮复制坐标/玩家名/港口。", panelX + PAD, footerTop + 6, COL_META, true);
		} else {
			g.drawString(this.font, "同类物品已整合：点击物品查看全部商店（出售价低→高 / 收购价高→低）。", panelX + PAD, footerTop + 6, COL_META, true);
		}

		if (mode == Mode.SHOPS) {
			boolean hasSel = selectedIndex >= 0 && selectedIndex < currentGroupShops.size();
			drawFlatButton(g, btnCopyPos, "复制坐标", hasSel && isHover(btnCopyPos));
			drawFlatButton(g, btnCopyOwner, "复制玩家", hasSel && isHover(btnCopyOwner));
			drawFlatButton(g, btnCopyGateway, "复制港口", hasSel && isHover(btnCopyGateway));
			if (copiedLabel != null && System.currentTimeMillis() < copiedUntil) {
				String tip = "已复制: " + copiedLabel;
				g.drawString(this.font, truncate(tip, panelW - PAD * 2 - btnCopyPos.w - 16), panelX + PAD, footerTop + 32, opaque(0x55FF55), true);
			}
		}
	}

	private boolean emptyListMessage(GuiGraphics g) {
		List<?> rows = currentRows();
		if (loading) {
			g.drawString(this.font, "正在从数据库读取商店数据…", panelX + PAD, listTop + 6, COL_TITLE, true);
			return true;
		}
		if (!rows.isEmpty() && loadError == null) return false;
		String msg;
		if (loadError != null) return false;
		if (allRecords.isEmpty()) {
			msg = "数据库暂无数据：进服走一走商店区（或按捕捉键 K）即可采集。";
		} else if (filtered.isEmpty()) {
			msg = "没有符合条件的结果（注意：满仓/缺货默认隐藏，可点上方按钮显示）。";
		} else {
			msg = "该条件下没有商店。";
		}
		g.drawString(this.font, truncate(msg, panelW - PAD * 2 - 4), panelX + PAD, listTop + 6, COL_META, true);
		return true;
	}

	/** 整合视图行：行1 [n家] 物品名 +（右对齐）售/收价格区间；行2 汇总 */
	private void drawGroupRow(GuiGraphics g, Group group, int x, int y, int maxW) {
		String tag = "[" + group.shops.size() + "家]";
		g.drawString(this.font, tag, x, y + 1, COL_TITLE, true);

		String item = " " + group.item;
		String sellRange = rangeText(group, true);
		String buyRange = rangeText(group, false);
		StringBuilder right = new StringBuilder();
		if (sellRange != null) right.append("售 ").append(sellRange);
		if (buyRange != null) {
			if (right.length() > 0) right.append("  ");
			right.append("收 ").append(buyRange);
		}
		int rightW = this.font.width(right.toString());
		int itemMaxW = maxW - 46 - rightW - 8;
		g.drawString(this.font, this.font.plainSubstrByWidth(item, Math.max(8, itemMaxW)),
				x + 46, y + 1, COL_ROW, true);
		if (right.length() > 0) {
			g.drawString(this.font, right.toString(), x + maxW - rightW, y + 1, COL_PRICE, true);
		}

		StringBuilder meta = new StringBuilder("  ");
		int sellN = 0;
		int buyN = 0;
		for (ShopRecord r : group.shops) {
			if (ShopSignParser.TYPE_SELL.equals(r.type())) sellN++;
			else if (ShopSignParser.TYPE_BUY.equals(r.type())) buyN++;
		}
		if (sellN > 0) meta.append("出售").append(sellN).append("家 ");
		if (buyN > 0) meta.append("收购").append(buyN).append("家 ");
		int soldN = group.shops.size() - sellN - buyN;
		if (soldN > 0) meta.append("满/缺").append(soldN).append("家");
		g.drawString(this.font, this.font.plainSubstrByWidth(meta.toString(), maxW - 2),
				x + 2, y + 11, COL_META, true);
	}

	private String rangeText(Group group, boolean sell) {
		Double min = group.min(sell);
		if (min == null) return null;
		Double max = group.max(sell);
		if (min.equals(max)) return "$" + formatPrice(min);
		return "$" + formatPrice(min) + "~$" + formatPrice(max);
	}

	/** 商店条目两行：行1 [类型] 物品 ×n……价格右对齐；行2 店主 · 领地 · 港 */
	private void drawEntry(GuiGraphics g, ShopRecord r, int x, int y, int maxW, boolean selected) {
		int typeIdx = typeColorIndex(r);
		String tag = "[" + typeText(r) + "]";
		g.drawString(this.font, tag, x, y + 1, opaque(TYPE_COLORS[typeIdx]), true);

		StringBuilder item = new StringBuilder(" ").append(r.item());
		if (r.quantity() == ShopSignParser.QUANTITY_INFINITE) {
			item.append("（无限）");
		} else if (r.quantity() > 0) {
			item.append(" ×").append(r.quantity());
		}
		String priceText = "$" + formatPrice(r.price());
		int priceW = this.font.width(priceText);
		int itemMaxW = maxW - 42 - priceW - 8;
		g.drawString(this.font, this.font.plainSubstrByWidth(item.toString(), Math.max(8, itemMaxW)),
				x + 42, y + 1, selected ? COL_WHITE : COL_ROW, true);
		g.drawString(this.font, priceText, x + maxW - priceW, y + 1, COL_PRICE, true);

		StringBuilder meta = new StringBuilder("  ").append(r.owner());
		if (!r.land().isEmpty()) meta.append(" · ").append(r.land());
		if (!r.gateway().isEmpty()) meta.append(" · 港:").append(r.gateway());
		g.drawString(this.font, this.font.plainSubstrByWidth(meta.toString(), maxW - 2),
				x + 2, y + 11, selected ? opaque(0xBBCCDD) : COL_META, true);
	}

	/** 价格格式化：$ + 千分位；小数最多两位（$1,000,000 / $0.5） */
	private static String formatPrice(double v) {
		if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
			return String.format(Locale.US, "%,d", (long) v);
		}
		return new DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(v);
	}

	private void drawPanelFrame(GuiGraphics g) {
		g.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1, COL_BG);
		g.fill(panelX, panelY, panelX + panelW, panelY + 1, COL_BORDER);
		g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, COL_BORDER);
		g.fill(panelX, panelY, panelX + 1, panelY + panelH, COL_BORDER);
		g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, COL_BORDER);
		g.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 17, COL_HEADER);
		g.fill(panelX + panelW - 15, panelY + 3, panelX + panelW - 4, panelY + 14, 0x40FFFFFF);
		g.drawString(this.font, "X", panelX + panelW - 12, panelY + 4, COL_WHITE, false);
	}

	/** 自绘扁平按钮：深色底 + hover 提亮 + 白色文字；highlighted 常亮描边 */
	private void drawFlatButton(GuiGraphics g, Rect rect, String label, boolean highlighted) {
		boolean hover = isHover(rect);
		int bg = highlighted ? 0xFF2E6E8E : hover ? 0xFF2A4A5E : 0xFF16303E;
		g.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, bg);
		g.fill(rect.x, rect.y + rect.h - 1, rect.x + rect.w, rect.y + rect.h, COL_BORDER);
		int tw = this.font.width(label);
		g.drawString(this.font, label, rect.x + (rect.w - tw) / 2, rect.y + (rect.h - 8) / 2, COL_WHITE, false);
	}

	private boolean isHover(Rect rect) {
		return rect.contains(hoverX, hoverY);
	}

	private static int typeColorIndex(ShopRecord r) {
		return switch (r.type()) {
			case ShopSignParser.TYPE_SELL -> 1;
			case ShopSignParser.TYPE_BUY -> 2;
			case ShopSignParser.TYPE_SOLD_OUT -> 3;
			case ShopSignParser.TYPE_NO_SPACE -> 4;
			default -> r.type().isEmpty() ? 0 : 4;
		};
	}

	/** 显示名：空间不足 → 满仓（数据库值不变，仅 UI 用词） */
	private static String typeText(ShopRecord r) {
		return switch (r.type()) {
			case ShopSignParser.TYPE_SELL -> "出售";
			case ShopSignParser.TYPE_BUY -> "收购";
			case ShopSignParser.TYPE_SOLD_OUT -> "缺货";
			case ShopSignParser.TYPE_NO_SPACE -> "满仓";
			default -> r.type().isEmpty() ? "记录" : r.type();
		};
	}

	private String detailLine1(ShopRecord r) {
		return "坐标 " + r.x() + ", " + r.y() + ", " + r.z() + "（" + r.world() + "，" + r.side() + "面）"
				+ "   服务器: " + r.server() + "   单价: $" + formatPrice(r.price());
	}

	private String detailLine2(ShopRecord r) {
		StringBuilder sb = new StringBuilder("牌面: ").append(r.priceRaw()).append("   牌面原文: ").append(r.raw());
		if (!r.land().isEmpty()) sb.append("   领地: ").append(r.land());
		if (!r.gateway().isEmpty()) sb.append("   最近港口: ").append(r.gateway());
		return sb.toString();
	}

	private String truncate(String s, int maxWidth) {
		return this.font.plainSubstrByWidth(s, maxWidth);
	}

	private boolean isMouseInList(double mouseX, double mouseY) {
		return mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= listTop && mouseY < listBottom;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double x = event.x();
		double y = event.y();
		// 关闭按钮
		if (x >= panelX + panelW - 15 && x <= panelX + panelW - 4 && y >= panelY + 3 && y <= panelY + 14) {
			onClose();
			return true;
		}
		// 顶部按钮
		if (btnFilter.contains(x, y)) {
			typeFilterIndex = (typeFilterIndex + 1) % FILTER_NAMES.length;
			refilter();
			scrollOffset = 0;
			selectedIndex = -1;
			return true;
		}
		if (btnSold.contains(x, y)) {
			showSoldStates = !showSoldStates;
			refilter();
			scrollOffset = 0;
			selectedIndex = -1;
			return true;
		}
		if (btnRefresh.contains(x, y)) {
			reload();
			scrollOffset = 0;
			selectedIndex = -1;
			return true;
		}
		// 返回整合视图
		if (mode == Mode.SHOPS && btnBack.contains(x, y)) {
			mode = Mode.GROUPS;
			currentGroupItem = null;
			currentGroupShops = List.of();
			selectedIndex = -1;
			scrollOffset = 0;
			return true;
		}
		// 复制按钮
		if (mode == Mode.SHOPS && selectedIndex >= 0 && selectedIndex < currentGroupShops.size()) {
			ShopRecord r = currentGroupShops.get(selectedIndex);
			if (btnCopyPos.contains(x, y)) {
				copy(coordString(r).replace(',', ' '), "坐标");
				return true;
			}
			if (btnCopyOwner.contains(x, y)) {
				copy(r.owner(), "玩家名");
				return true;
			}
			if (btnCopyGateway.contains(x, y)) {
				String gw = r.gateway();
				int cut = gw.indexOf(" (");
				copy(cut > 0 ? gw.substring(0, cut) : gw, "港口名");
				return true;
			}
		}
		// 列表点击：整合视图 → 进入物品商店列表；商店视图 → 选中
		if (isMouseInList(x, y)) {
			int i = (int) ((y - listTop) / ROW_HEIGHT);
			int idx = scrollOffset + i;
			if (i >= 0 && idx < currentRows().size()) {
				if (mode == Mode.GROUPS) {
					Group group = groups.get(idx);
					currentGroupItem = group.item;
					currentGroupShops = new ArrayList<>(group.shops);
					sortShops(currentGroupShops);
					mode = Mode.SHOPS;
					selectedIndex = -1;
					scrollOffset = 0;
				} else {
					selectedIndex = idx;
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	/** 写入系统剪贴板并显示 1.5 秒反馈 */
	private void copy(String text, String label) {
		if (text == null || text.isEmpty()) return;
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		copiedLabel = label;
		copiedUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && isMouseInList(mouseX, mouseY)) {
			scrollOffset -= (int) Math.signum(scrollY) * 3;
			int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
			int maxOffset = Math.max(0, currentRows().size() - visibleRows);
			scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// 输入框聚焦时第一次 ESC 只交出焦点，第二次才关面板
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && searchBox != null && searchBox.isFocused()) {
			setFocused(null);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(null);
	}
}
