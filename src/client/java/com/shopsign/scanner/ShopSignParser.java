package com.shopsign.scanner;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuickShop 风格箱子商店告示牌解析，严格 4 行格式：
 * 第 1 行 玩家名；第 2 行 状态行（出售 N / 收购 N / 缺货 / 空间不足）；第 3 行 物品名；第 4 行 单价。
 * 状态与价格关键词支持多语言（简中/繁中/英语默认，可在配置中扩展），匹配前剥离颜色代码。
 */
public final class ShopSignParser {
	public static final String TYPE_SELL = "sell";
	public static final String TYPE_BUY = "buy";
	public static final String TYPE_SOLD_OUT = "sold_out";
	public static final String TYPE_NO_SPACE = "no_space";

	/** 无限数量的数量值（服务器官方商店）：txt/数据库都用 -1 表示 */
	public static final int QUANTITY_INFINITE = -1;

	private static final Pattern INT = Pattern.compile("\\d+");
	private static final Pattern DECIMAL = Pattern.compile("-?\\d+(?:\\.\\d+)?");
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Pattern FORMATTING = Pattern.compile("§[0-9A-Fa-fK-Ok-oRrXx]");

	private ShopSignParser() {
	}

	/**
	 * @param lines  告示牌某一面的 4 行文本
	 * @param config 决定严格玩家名校验与各关键词集合
	 */
	public static Optional<ParsedSign> parse(String[] lines, ModConfig config) {
		if (lines == null || lines.length < 4) return Optional.empty();

		String ownerLine = clean(lines[0]);
		String statusLine = clean(lines[1]);
		String itemLine = clean(lines[2]);
		String priceLine = clean(lines[3]);

		if (ownerLine.isEmpty() || statusLine.isEmpty() || itemLine.isEmpty() || priceLine.isEmpty()) {
			return Optional.empty();
		}
		if (config.strictName && !PLAYER_NAME.matcher(ownerLine).matches()) return Optional.empty();

		Status status = matchStatus(statusLine, config);
		if (status == null) return Optional.empty();

		Double price = matchPrice(priceLine, config);
		if (price == null) return Optional.empty();

		String raw = String.join("·", ownerLine, statusLine, itemLine, priceLine);
		return Optional.of(new ParsedSign(ownerLine, status.type(), status.quantity(), itemLine, price, priceLine, raw));
	}

	public record ParsedSign(String owner, String type, int quantity, String item, double price, String priceRaw, String raw) {
	}

	private record Status(String type, int quantity) {
	}

	/** 状态行：缺货/空间不足优先，其次官方无限数量商店，最后出售/收购（带数量） */
	private static Status matchStatus(String line, ModConfig config) {
		String lower = lower(line);
		for (String kw : config.soldOutKeywords) {
			if (startsWithKeyword(lower, kw)) return new Status(TYPE_SOLD_OUT, 0);
		}
		for (String kw : config.noSpaceKeywords) {
			if (startsWithKeyword(lower, kw)) return new Status(TYPE_NO_SPACE, 0);
		}
		// 官方无限数量商店：状态行带「无限」类词且含出售/收购词（如「无限出售」「出售：无限」）。
		// 这里用 contains 而非行首匹配，因为「无限」经常写在出售词前面。
		if (containsAny(lower, config.infiniteKeywords)) {
			for (String kw : config.sellKeywords) {
				if (containsKeyword(lower, kw)) return new Status(TYPE_SELL, QUANTITY_INFINITE);
			}
			for (String kw : config.buyKeywords) {
				if (containsKeyword(lower, kw)) return new Status(TYPE_BUY, QUANTITY_INFINITE);
			}
		}
		for (String kw : config.sellKeywords) {
			if (startsWithKeyword(lower, kw)) return new Status(TYPE_SELL, extractIntOrZero(line));
		}
		for (String kw : config.buyKeywords) {
			if (startsWithKeyword(lower, kw)) return new Status(TYPE_BUY, extractIntOrZero(line));
		}
		return null;
	}

	/** 忽略大小写的包含匹配（用于无限数量行的出售/收购词探测） */
	private static boolean containsKeyword(String lowerLine, String keyword) {
		if (keyword == null || keyword.isBlank()) return false;
		return lowerLine.contains(lower(keyword.trim()));
	}

	private static boolean containsAny(String lowerLine, String[] keywords) {
		for (String kw : keywords) {
			if (containsKeyword(lowerLine, kw)) return true;
		}
		return false;
	}

	/**
	 * 价格上限。
	 *
	 * 数据库列是 DECIMAL(20,4)，最多 16 位整数。超过这个量级的数值几乎一定是误解析
	 * （牌面上出现了超长数字），而一旦写入失败，2.2.0 及更早版本的上传管线会把这条记录
	 * **永久重排队**并反复触发 60 秒重连退避 —— 一条坏数据就能让整个 mod 看起来
	 * 「连不上数据库」。这里在解析阶段就判为「不是有效价格」，该牌面按非商店处理。
	 *
	 * @see com.shopsignscanner.ColumnLimits#MAX_PRICE 值的唯一来源
	 */
	private static final double MAX_PRICE = com.shopsignscanner.ColumnLimits.MAX_PRICE;

	/** 价格行必须以价格关键词开头，且剩余文本中含 (0, MAX_PRICE] 的有限正数 */
	private static Double matchPrice(String line, ModConfig config) {
		String lower = lower(line);
		boolean hasKeyword = false;
		for (String kw : config.priceKeywords) {
			if (startsWithKeyword(lower, kw)) {
				hasKeyword = true;
				break;
			}
		}
		if (!hasKeyword) return null;
		Double value = extractDecimal(line);
		if (value == null) return null;
		// NaN / Infinity / 非正数 / 超出可入库范围：一律视为无效价格
		if (!Double.isFinite(value) || value <= 0 || value > MAX_PRICE) return null;
		return value;
	}

	/** 忽略大小写的行首关键词匹配；关键词为空视为不匹配 */
	private static boolean startsWithKeyword(String lowerLine, String keyword) {
		if (keyword == null || keyword.isBlank()) return false;
		return lowerLine.startsWith(lower(keyword.trim()));
	}

	private static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}

	private static Integer extractInt(String s) {
		String normalized = THOUSANDS_SEPARATOR.matcher(s).replaceAll("");
		Matcher m = INT.matcher(normalized);
		if (!m.find()) return null;
		try {
			return Integer.parseInt(m.group());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 出售/收购行的数量。状态行以关键词开头但不含数字时（如玩家只写「出售」或
	 * 「Selling」没写数量）返回 0 —— 不能让 null 拆箱成 NPE，
	 * 那会在扫描循环里直接崩溃游戏（2026-09-26 事故）。
	 */
	private static int extractIntOrZero(String s) {
		Integer v = extractInt(s);
		return v == null ? 0 : v;
	}

	/**
	 * 从价格行中取数值。
	 *
	 * 修复（v2.0.0）：原实现直接在原文上跑 `-?\d+(?:\.\d+)?`，遇到千分位逗号会截断：
	 *   `单价：$2,000`   → 2      （正确值 2000，差 1000 倍）
	 *   `单价：$48,888`  → 48     （正确值 48888）
	 * 因此先把「数字之间、后面恰好跟 3 位数字」的逗号去掉，再去匹配数值。
	 * 该规则不会误伤小数点（`,` 不是小数点），也不会动 `$1.5` 这类值。
	 * 数量行同步受益（如 `收购 1,601`）。
	 */
	private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

	private static Double extractDecimal(String s) {
		String normalized = THOUSANDS_SEPARATOR.matcher(s).replaceAll("");
		Matcher m = DECIMAL.matcher(normalized);
		if (!m.find()) return null;
		try {
			return Double.parseDouble(m.group());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 剥离颜色/格式代码与两侧空白，并把 | 替换为 / 以免破坏 txt 列结构 */
	private static String clean(String s) {
		if (s == null) return "";
		s = FORMATTING.matcher(s).replaceAll("");
		return s.trim().replace('|', '/');
	}
}

