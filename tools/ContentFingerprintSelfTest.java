import com.shopsignscanner.ContentFingerprint;

import java.util.HashSet;
import java.util.Set;

/**
 * ContentFingerprint 自检（2.2.2 P2-⑪）。
 *
 * <p>这张指纹表是「商店内容有没有变」的唯一判据：指纹一旦退化成高碰撞率、
 * 或对相似输入失去区分度，mod 就会把真实改动误判为「没变」而漏上传，
 * 网站停在旧数据上，且没有任何报错提示。
 *
 * <p>本文件只用纯逻辑类（不碰任何 Minecraft 类型），可被独立编译运行。
 */
public final class ContentFingerprintSelfTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testSameInputSameFingerprint();
		testDifferentInputDifferentFingerprint();
		testEmptyAndNull();
		testSignLikeStringsDistinguish();
		testDistributionOverManySigns();
		testChangedFieldIsDetected();
		testEqualsContract();

		System.out.println();
		System.out.println("ContentFingerprintSelfTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}

	/**
	 * 同一条内容反复算必须得到同一个值。
	 * 否则玩家站在店里不动，每轮扫描都判为「有变化」而反复入队上传。
	 */
	private static void testSameInputSameFingerprint() {
		String[] samples = {
				"iamnewbee·售卖·钻石·1000",
				"owner·收购·圆石·-64",
				"店主·无限量·下界合金锭·250000",
				"",
				"a",
		};
		int stable = 0;
		for (String s : samples) {
			ContentFingerprint a = ContentFingerprint.of(s, "", "");
			ContentFingerprint b = ContentFingerprint.of(s, "", "");
			ContentFingerprint c = ContentFingerprint.of(s, "", "");
			if (a.equals(b) && b.equals(c)) stable++;
		}
		check("相同输入总是得到相同指纹（多次调用）", stable == samples.length);
	}

	/** 不同内容必须得到不同指纹，尤其是只差一位的价格 */
	private static void testDifferentInputDifferentFingerprint() {
		String a = "iamnewbee·售卖·钻石·1000";
		String b = "iamnewbee·售卖·钻石·1001";
		check("只差一位的价格得到不同指纹",
				!ContentFingerprint.of(a, "", "").equals(ContentFingerprint.of(b, "", "")));

		String c = "iamnewbee·售出·钻石·1000";
		check("只差一个动作字得到不同指纹",
				!ContentFingerprint.of(a, "", "").equals(ContentFingerprint.of(c, "", "")));

		check("完全不同的内容得到不同指纹",
				!ContentFingerprint.of(a, "", "").equals(ContentFingerprint.of("xyz", "other", "port")) );
	}

	private static void testEmptyAndNull() {
		ContentFingerprint empty = ContentFingerprint.of("", "", "");
		int nullA = ContentFingerprint.of(null, "", "").raw;
		check("空串与 null 视为同一内容（都不产生指纹）", empty.raw == nullA);
		check("空串指纹是稳定的 0", empty.raw == 0);
		check("空串与任意非空内容不同",
				empty.raw != ContentFingerprint.of("x", "", "").raw);
	}

	/**
	 * 牌面字符串的共同点：结构相似、只差一两个字符。
	 * String.hashCode() 在这类输入上会撞（"Aa" 与 "BB" 同为 2112），
	 * xxHash32 不应该。
	 */
	private static void testSignLikeStringsDistinguish() {
		String[][] pairs = {
				{"Aa", "BB"},
				{"卖钻石", "卖钻石块"},
				{"owner·售卖·钻石·1000", "owner·售卖·钻石·2000"},
				{"ab", "ba"},
				{"售价1", "售价2"},
				{"长者之湖", "长着之湖"},
		};
		int distinct = 0;
		for (String[] p : pairs) {
			if (ContentFingerprint.hash(p[0]) != ContentFingerprint.hash(p[1])) distinct++;
		}
		check("结构相似的牌面全部得到不同指纹（" + distinct + "/" + pairs.length + "）",
				distinct == pairs.length);
	}

	/**
	 * 10 万条模拟商店的指纹分布：碰撞数必须接近 32 位哈希的理论值
	 * （生日悖论约 n^2/2^33 ≈ 116 次）。高于此说明摘要质量差，
	 * 商店改名改价就可能被漏掉。
	 */
	private static void testDistributionOverManySigns() {
		int n = 100_000;
		int[] hashes = new int[n];
		for (int i = 0; i < n; i++) {
			String owner = "owner" + (i % 5000);
			String item = (i % 3 == 0 ? "钻石" : i % 3 == 1 ? "圆石" : "下界合金锭");
			String price = String.valueOf((i * 37) % 500_000);
			hashes[i] = ContentFingerprint.hash(owner + "·" + item + "·" + price);
		}
		Set<Integer> unique = new HashSet<>();
		int collisions = 0;
		for (int h : hashes) {
			if (!unique.add(h)) collisions++;
		}
		// 理论期望约 116，阈值放到 1000 仍远严于「分布坏掉」的量级
		System.out.println("（10 万条模拟商店的指纹碰撞数: " + collisions + "，理论期望约 116）");
		check("10 万条模拟商店的指纹碰撞数在合理范围", collisions < 1000);
	}

	/**
	 * 三元组里任一项变化都必须被检出 —— 这正是 2.2.2 修复
	 * 「land/gateway 不参与变化判定」的回归测试。
	 */
	private static void testChangedFieldIsDetected() {
		ContentFingerprint base = ContentFingerprint.of("owner·售卖·钻石·1000", "主城", "北港");
		check("raw 变化被检出",
				!base.equals(ContentFingerprint.of("owner·售卖·钻石·999", "主城", "北港")));
		check("land 变化被检出",
				!base.equals(ContentFingerprint.of("owner·售卖·钻石·1000", "商业区", "北港")));
		check("gateway 变化被检出",
				!base.equals(ContentFingerprint.of("owner·售卖·钻石·1000", "主城", "南港")));
		check("三项都没变判为相同",
				base.equals(ContentFingerprint.of("owner·售卖·钻石·1000", "主城", "北港")));
		check("三项都变也判为不同",
				!base.equals(ContentFingerprint.of("owner·收·钻石·888", "野外", "南港")));
	}

	/** 指纹要进 HashMap，equals/hashCode 必须自洽 */
	private static void testEqualsContract() {
		ContentFingerprint base = ContentFingerprint.of("abc", "land", "port");
		check("hashCode 与自己一致", base.hashCode() == base.hashCode());
		check("相等则 hashCode 相等",
				base.equals(ContentFingerprint.of("abc", "land", "port"))
						&& base.hashCode() == ContentFingerprint.of("abc", "land", "port").hashCode());
		check("equals(null) 为 false", !base.equals(null));

		Set<ContentFingerprint> set = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			set.add(ContentFingerprint.of("abc", "land", "port"));
		}
		check("同一串重复入 Set 只保留一份", set.size() == 1);

		set.clear();
		set.add(ContentFingerprint.of("abc", "land", "port"));
		set.add(ContentFingerprint.of("abc", "land", "port2"));
		check("不同指纹在 Set 里是两份", set.size() == 2);
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("[PASS] " + name);
		} else {
			failed++;
			System.out.println("[FAIL] " + name);
		}
	}

	private ContentFingerprintSelfTest() {
	}
}
