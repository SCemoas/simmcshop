package com.shopsign.scanner;

import java.util.Locale;

/**
 * 数据库错误的分类与玩家提示。
 *
 * <h2>为什么单独抽成一个类</h2>
 * 这段逻辑决定「一条失败的记录该丢弃还是该重试」，是 2.2.1 的核心修复点，
 * 必须能被测试。它原本在 {@link DatabaseReporter} 里，而那是个引用了
 * Minecraft/Fabric 的类 —— 在测试 JVM 里根本无法加载。
 * 抽出来之后这里是**纯 Java、零外部依赖**，可以用 javac 单独编译并直接跑断言。
 *
 * @see #isDataError(Throwable)
 */
final class DbErrors {

	private DbErrors() {
	}

	/**
	 * 是否「数据被数据库拒绝」类错误 —— 这类错误重试永远不会成功，必须丢弃记录。
	 *
	 * <p>判定依据（任一命中即可）：
	 * <ul>
	 *   <li>{@link java.sql.DataTruncation} —— MySQL 越界/截断抛的
	 *       {@code MysqlDataTruncation} 继承自它</li>
	 *   <li>{@link java.sql.SQLDataException} —— SQL 标准里 class 22 的基类</li>
	 *   <li>SQLState 以 {@code 22} 开头（class 22 = data exception）</li>
	 *   <li>消息含 out of range / data truncation / data too long（跨驱动兜底）</li>
	 * </ul>
	 *
	 * <p><b>为什么必须区分</b>：2.2.0 及更早版本把所有异常都当连接故障处理
	 * （重新入队 + 关连接 + 60 秒退避）。一条价格越界的记录因此永远留在队列里，
	 * 反复触发重连，整条上传管线被永久堵死 ——
	 * 玩家看到的是「mod 老是连不上数据库」，而真实原因与网络无关。
	 */
	static boolean isDataError(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof java.sql.DataTruncation) return true;
			if (t instanceof java.sql.SQLDataException) return true;
			if (t instanceof java.sql.SQLException se) {
				String state = se.getSQLState();
				if (state != null && state.startsWith("22")) return true;
			}
			String msg = t.getMessage();
			if (msg != null) {
				String m = msg.toLowerCase(Locale.ROOT);
				if (m.contains("out of range") || m.contains("data truncation")
						|| m.contains("data too long")) {
					return true;
				}
			}
			if (t == t.getCause()) break; // 自引用兜底，避免死循环
		}
		return false;
	}

	/** 把 JDBC 异常翻译成玩家能理解的处置提示 */
	static String hintFor(String err) {
		if (err == null || err.isBlank()) return "";
		String e = err.toLowerCase(Locale.ROOT);
		if (e.contains("out of range") || e.contains("data truncation") || e.contains("data too long")
				|| e.contains("超出") || e.contains("可表示范围")) {
			return "数据被数据库拒绝（已跳过该条）";
		}
		if (e.contains("not allowed to connect")) return "IP未放行";
		if (e.contains("access denied")) return "账号或密码错误";
		if (e.contains("blocked because of many connection errors")) return "host被锁需flush-hosts";
		if (e.contains("unknown database")) return "库名不存在";
		if (e.contains("communications") || e.contains("timed out") || e.contains("timeout")) return "网络不通/安全组";
		if (e.contains("table") && e.contains("doesn't exist")) return "表缺失";
		return "";
	}

	/**
	 * 该提示是否属于「连接/权限」类。
	 *
	 * <p>只有这类才应该在日志里附上「本机出口 IP，请加入数据库白名单」。
	 * 数据错误提示这句话会把玩家引向完全错误的排查方向
	 * （2.2.0 的实际日志就是 {@code 连接失败（）… 需加入数据库白名单}，
	 * 而当时白名单根本没有问题）。
	 */
	static boolean isConnectivityHint(String hint) {
		return "IP未放行".equals(hint)
				|| "网络不通/安全组".equals(hint)
				|| "host被锁需flush-hosts".equals(hint);
	}
}
