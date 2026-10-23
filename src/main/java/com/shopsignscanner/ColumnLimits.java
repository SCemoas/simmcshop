package com.shopsignscanner;

/**
 * 列宽与取值上限的<b>唯一来源</b>。
 *
 * <h2>为什么要有这个类</h2>
 * 2.2.1 之前同一个上限在三个地方各写一份，且都只写「价格」：
 * <ul>
 *   <li>{@code ShopSignParser.MAX_PRICE = 1e15}（解析阶段判无效价格）</li>
 *   <li>{@code DatabaseReporter.MAX_DB_PRICE = 1e15}（入库前再判一次）</li>
 *   <li>{@code ModConfig.intervalTicks = 100} vs {@code sanitize()} 的下限 200
 *       （声明值比实际下限还小，玩家改配置后第一次读到的和被写入的值不一致）</li>
 * </ul>
 * 数字一旦要跟着表结构变，就得逐处找齐；漏改一处就会出现「解析器放行、
 * 数据库拒绝」的半截状态 —— 而这类记录重试永远不会成功，
 * 在 2.2.1 的分拣逻辑里会被整条丢弃（见 {@code DatabaseReporter.insert}）。
 *
 * <p><b>下限（MIN_*）与上限同样是同源</b>：超长文本在<b>入库前</b>按列宽安全截断
 * （见 {@link #clamp(String, int)}），而不是留给数据库抛 Data truncation。
 * 前者最多丢尾部几个字符，后者会整条 INSERT 失败并被当作数据错误丢弃。
 */
public final class ColumnLimits {

	/** 价格上限：DECIMAL(20,4) 最多 16 位整数，取 1e15 留一个数量级余量 */
	public static final double MAX_PRICE = 1e15;

	// -----------------------------------------------------------------
	// shop_signs 列宽（必须与 DatabaseReporter.CREATE_SQL 一致）
	// -----------------------------------------------------------------
	public static final int OWNER_MAX = 64;
	public static final int TYPE_MAX = 16;
	public static final int ITEM_MAX = 255;
	public static final int PRICE_RAW_MAX = 255;
	public static final int WORLD_MAX = 128;
	public static final int SERVER_MAX = 255;
	public static final int RAW_MAX = 1024;
	public static final int LAND_MAX = 64;
	public static final int GATEWAY_MAX = 192;

	private ColumnLimits() {
	}

	/**
	 * 按列宽安全截断：{@code limit} 个<b>码点</b>（不是 char）。
	 *
	 * MySQL 的 VARCHAR(n) 按字符数计，不是字节数；按 char 截断会把
	 * 一个 BMP 之外的字符（如生僻字、emoji）劈成代理对的一半，
	 * 写进去会变成乱码。按码点截断则只会丢整个字符。
	 */
	public static String clamp(String s, int limit) {
		if (s == null) return "";
		if (limit <= 0) return "";
		int n = s.codePointCount(0, s.length());
		if (n <= limit) return s;
		return s.substring(0, s.offsetByCodePoints(0, limit));
	}
}
