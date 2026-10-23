package com.shopsignscanner;

/**
 * {@link ColumnLimits} 的自检程序（2.2.2 新增）。
 *
 * <h2>为什么测这个</h2>
 * 入库前截断是「一条记录要不要被整条丢弃」的分水岭：截断正确，
 * 商店只是尾部字符被砍；截断错误（按 char 而不是按码点、null 不兜底、
 * limit 边界差一），超长字符串仍然会被数据库抛 Data truncation，
 * 在 2.2.1 的分拣逻辑里整条丢弃 —— 商店凭空消失，且没有任何补救路径。
 *
 * <pre>
 *   javac -d out src/main/java/com/shopsignscanner/ColumnLimits.java \
 *         tools/ColumnLimitsSelfTest.java
 *   java -cp out com.shopsignscanner.ColumnLimitsSelfTest
 * </pre>
 */
public final class ColumnLimitsSelfTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// ---- clamp：列宽内的内容必须原样返回（不能白丢字符）----
		check("owner 64 字符原样返回",
				"钻石镐".equals(clamp("钻石镐", ColumnLimits.OWNER_MAX))
						&& "钻石镐".length() == 3);
		check("空串返回空串", "".equals(clamp("", ColumnLimits.ITEM_MAX)));
		check("恰好等于上限不截断",
				clamp("a".repeat(64), ColumnLimits.OWNER_MAX).length() == 64);
		check("上限+1 截到上限",
				clamp("a".repeat(65), ColumnLimits.OWNER_MAX).length() == 64);

		// ---- clamp：按码点而非按 char（关键）----
		// "🙂" 是 1 个码点 2 个 char（代理对）；item 上限 255。
		String emojis = "🙂".repeat(300);
		String clampedEmoji = clamp(emojis, ColumnLimits.ITEM_MAX);
		check("代理对按码点截断：255 个码点",
				clampedEmoji.codePointCount(0, clampedEmoji.length()) == 255);
		check("代理对截断后不残留半个代理对（偶数 char，成对出现）",
				clampedEmoji.length() == 510 && !Character.isHighSurrogate(
						clampedEmoji.charAt(clampedEmoji.length() - 1)));
		// 生僻字（BMP 之外同理：任何时候都不该出现 lone surrogate）
		check("截断结果不含孤立代理对", !hasLoneSurrogate(clampedEmoji));
		checkBMP("钻石镐".repeat(80) + "🙂", ColumnLimits.RAW_MAX);

		// ---- clamp：健壮性 ----
		check("null 返回空串（不抛 NPE）", "".equals(clamp(null, ColumnLimits.RAW_MAX)));
		check("limit 为 0 返回空串", "".equals(clamp("abc", 0)));
		check("limit 为负返回空串", "".equals(clamp("abc", -5)));
		check("limit 远大于长度时原样返回",
				"a".repeat(2000).equals(clamp("a".repeat(2000), 9999)));

		// ---- 牌面原文 raw 是最可能超限的字段（4 行拼接无上限）----
		String hugeRaw = "店主名超长的商店商人协会会员".repeat(100); // 1300 码点 > 1024
		String rawClamped = clamp(hugeRaw, ColumnLimits.RAW_MAX);
		check("raw 1024 上限生效", rawClamped.codePointCount(0, rawClamped.length()) == ColumnLimits.RAW_MAX);
		check("raw 截断后非空", rawClamped.length() > 0);

		// ---- MAX_PRICE：与 DECIMAL(20,4) 的关系 ----
		check("MAX_PRICE = 1e15", ColumnLimits.MAX_PRICE == 1e15);
		check("MAX_PRICE 低于 DECIMAL(20,4) 上限（16 位整数）",
				ColumnLimits.MAX_PRICE < 1e15 * 10);

		System.out.println();
		System.out.println(failed == 0
				? "全部通过：" + passed + " 项"
				: "失败 " + failed + " 项 / 共 " + (passed + failed) + " 项");
		if (failed != 0) System.exit(1);
	}

	/** 真实调用点：与 DatabaseReporter.insert 用的同一个 clamp */
	private static String clamp(String s, int limit) {
		return ColumnLimits.clamp(s, limit);
	}

	private static void checkBMP(String s, int limit) {
		check("BMP 外字符（生僻字）混排截断仍安全",
				!hasLoneSurrogate(ColumnLimits.clamp(s, limit)));
	}

	private static boolean hasLoneSurrogate(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
				i++;
			} else if (Character.isLowSurrogate(c)) {
				return true; // 没有配对的低位代理
			}
		}
		return false;
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
