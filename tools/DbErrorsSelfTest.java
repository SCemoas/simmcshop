package com.shopsign.scanner;

import java.sql.DataTruncation;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * {@link DbErrors} 的自检程序。
 *
 * <h2>为什么不用 JUnit</h2>
 * 本项目的 gradle 走 Fabric Loom，test sourceSet 需要整条 Minecraft 类路径，
 * 而这里要验证的是**纯逻辑**。所以写成一个带 main 的自检，
 * 用 javac/java 直接跑，零依赖、零构建系统：
 *
 * <pre>
 *   javac -d out src/client/java/com/shopsign/scanner/DbErrors.java \
 *         src/test/java/com/shopsign/scanner/DbErrorsSelfTest.java
 *   java -cp out com.shopsign.scanner.DbErrorsSelfTest
 * </pre>
 *
 * <p>断言的点都对应 2.2.0 真实踩过的坑（见 CHANGELOG 2.2.1）。
 */
public final class DbErrorsSelfTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// ---- isDataError：该丢弃的必须判为数据错误 ----
		// DataTruncation(index, parameter, read, dataSize, transferSize)
		// —— MySQL 抛的 MysqlDataTruncation 就继承自它
		check("DataTruncation 判为数据错误",
				DbErrors.isDataError(new DataTruncation(8, false, true, 100, 5)));
		check("DataTruncation 作为 cause 也能识别",
				DbErrors.isDataError(new RuntimeException("batch failed",
						new DataTruncation(8, false, true, 100, 5))));
		check("SQLDataException 判为数据错误",
				DbErrors.isDataError(new SQLDataException("bad value")));
		check("SQLState 22003（数值越界）判为数据错误",
				DbErrors.isDataError(new SQLException("Out of range value for column 'price' at row 1", "22003")));
		check("SQLState 22001（字符串过长）判为数据错误",
				DbErrors.isDataError(new SQLException("Data too long for column 'item'", "22001")));
		check("仅消息含 out of range 也判为数据错误（跨驱动兜底）",
				DbErrors.isDataError(new SQLException("Out of range value for column 'price'")));
		check("异常被包装在 cause 链里也能识别",
				DbErrors.isDataError(new RuntimeException("batch failed",
						new SQLException("Out of range value for column 'price'", "22003"))));

		// ---- isDataError：连接类错误绝不能判成数据错误（否则会被错误丢弃） ----
		check("通信异常不算数据错误",
				!DbErrors.isDataError(new SQLException("Communications link failure", "08S01")));
		check("超时不算数据错误",
				!DbErrors.isDataError(new SQLTimeoutException("timed out")));
		check("拒绝连接不算数据错误",
				!DbErrors.isDataError(new SQLException("Access denied for user 'shop'@'1.2.3.4'", "28000")));
		check("库不存在不算数据错误",
				!DbErrors.isDataError(new SQLException("Unknown database 'shop'", "42000")));
		check("null 不抛异常且不算数据错误", !DbErrors.isDataError(null));

		// ---- hintFor ----
		check("数据错误提示不再引向白名单",
				"数据被数据库拒绝（已跳过该条）".equals(
						DbErrors.hintFor("Data truncation: Out of range value for column 'price' at row 1")));
		check("IP 未放行提示",
				"IP未放行".equals(DbErrors.hintFor("Host '1.2.3.4' is not allowed to connect to this MySQL server")));
		check("账号密码错误提示",
				"账号或密码错误".equals(DbErrors.hintFor("Access denied for user 'shop'@'1.2.3.4'")));
		check("库名不存在提示", "库名不存在".equals(DbErrors.hintFor("Unknown database 'shop'")));
		check("网络不通提示", "网络不通/安全组".equals(DbErrors.hintFor("Communications link failure")));
		check("host 被锁提示",
				"host被锁需flush-hosts".equals(
						DbErrors.hintFor("Host '1.2.3.4' is blocked because of many connection errors")));
		check("空错误返回空提示", "".equals(DbErrors.hintFor("")) && "".equals(DbErrors.hintFor(null)));

		// ---- isConnectivityHint：决定要不要附加「请加白名单 IP」 ----
		check("IP未放行 → 应提示白名单", DbErrors.isConnectivityHint("IP未放行"));
		check("网络不通 → 应提示白名单", DbErrors.isConnectivityHint("网络不通/安全组"));
		check("host被锁 → 应提示白名单", DbErrors.isConnectivityHint("host被锁需flush-hosts"));
		check("数据错误 → 不应提示白名单", !DbErrors.isConnectivityHint("数据被数据库拒绝（已跳过该条）"));
		check("账号密码错误 → 与白名单无关", !DbErrors.isConnectivityHint("账号或密码错误"));
		check("空提示 → 不提示白名单", !DbErrors.isConnectivityHint(""));

		// ---- 回归：2.2.0 的真实日志内容 ----
		String realLog = "com.mysql.cj.jdbc.exceptions.MysqlDataTruncation: Data truncation: "
				+ "Out of range value for column 'price' at row 1";
		check("2.2.0 真实报错被判为数据错误（此前被误判为连接失败）", DbErrors.isDataError(new SQLException(realLog)));
		check("2.2.0 真实报错不再附加白名单提示",
				!DbErrors.isConnectivityHint(DbErrors.hintFor(realLog)));

		System.out.println();
		System.out.println(failed == 0
				? "全部通过：" + passed + " 项"
				: "失败 " + failed + " 项 / 共 " + (passed + failed) + " 项");
		if (failed != 0) System.exit(1);
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  [OK]   " + name);
		} else {
			failed++;
			System.out.println("  [FAIL] " + name);
		}
	}
}
