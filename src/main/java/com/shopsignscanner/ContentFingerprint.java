package com.shopsignscanner;

/**
 * 商店内容指纹：把 (raw, land, gateway) 三元组压缩成三个 32 位摘要。
 *
 * <p>「商店内容有没有变」是扫描器每轮都要做的判定，判定需要把上一次的产出
 * 和这一次的产出做比较。存原文的问题是内存：一张牌的 raw 是四行牌面
 * 无缝拼接，平均几十字节，加上 land/gateway 两条，数万个已见商店就是
 * 几 MB 常驻堆，而它们的唯一用途就是这一处比较。
 *
 * <p>改用固定宽度摘要后每个商店只留 12 字节，内存占用降到三分之一以下。
 *
 * <p><b>为什么分三项而不是拼成一个串哈希一次</b>：拼起来也能正确判「有没有变」，
 * 但排障时看不出是哪一项变了；分开放着可以直接回答「是领地改名还是店主改价」。
 */
public final class ContentFingerprint {

	/** raw 牌面原文的内容摘要 */
	public final int raw;
	/** 领地名摘要 */
	public final int land;
	/** 最近港口摘要 */
	public final int gateway;

	public ContentFingerprint(int raw, int land, int gateway) {
		this.raw = raw;
		this.land = land;
		this.gateway = gateway;
	}

	/** 由三项原文构造指纹 */
	public static ContentFingerprint of(String raw, String land, String gateway) {
		return new ContentFingerprint(hash(raw), hash(land), hash(gateway));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ContentFingerprint that)) return false;
		return this.raw == that.raw && this.land == that.land && this.gateway == that.gateway;
	}

	@Override
	public int hashCode() {
		int h = raw;
		h = 31 * h + land;
		h = 31 * h + gateway;
		return h;
	}

	@Override
	public String toString() {
		return "ContentFingerprint{raw=" + Integer.toHexString(raw)
				+ ", land=" + Integer.toHexString(land)
				+ ", gateway=" + Integer.toHexString(gateway) + "}";
	}

	/**
	 * 32 位内容摘要（xxHash32 的小输入路径实现）。
	 *
	 * <p>为什么不直接用 {@link String#hashCode()}：hashCode 对短 ASCII 串的
	 * 雪崩效应差（{@code "Aa"} 与 {@code "BB"} 同为 2112），
	 * 而牌面正是「结构相似、只差一两个字符」的短串 ——
	 * 相似输入挤在同一小簇里，碰撞率远高于生日悖论的理论值，
	 * 真实改动就可能被误判成「没变」而漏上传。
	 *
	 * <p>也不上 64 位：那会让每商店的内存翻倍，而 32 位在 10 万商店规模下
	 * 的理论碰撞数约 116 次，见 tools/ContentFingerprintSelfTest。可接受。
	 */
	public static int hash(String s) {
		if (s == null) return 0;
		final int len = s.length();
		if (len == 0) return 0;
		final int PRIME1 = 0x9E3779B1;
		final int PRIME2 = 0x85EBCA77;
		final int PRIME3 = 0xC2B2AE3D;

		int h = len + PRIME3;
		int i = 0;
		// 每次吃 4 个 char（本地用 UTF-16 码元，与标准 xxh32 的 4 字节对齐同构）
		for (; i + 4 <= len; i += 4) {
			int k1 = s.charAt(i) | (s.charAt(i + 1) << 16);
			int k2 = s.charAt(i + 2) | (s.charAt(i + 3) << 16);
			h = Integer.rotateLeft(h + k1 * PRIME2, 17) * PRIME1;
			h = Integer.rotateLeft(h + k2 * PRIME2, 17) * PRIME1;
		}
		// 尾部不足 4 个
		if (i < len) {
			int k = 0;
			int shift = 0;
			for (; i < len; i++, shift += 16) {
				k |= s.charAt(i) << shift;
			}
			h = Integer.rotateLeft(h + k * PRIME3, 11) * PRIME1;
		}
		// 终混（avalanche）
		h ^= h >>> 15;
		h *= PRIME2;
		h ^= h >>> 13;
		h *= PRIME3;
		h ^= h >>> 16;
		return h;
	}

	private ContentFingerprint() {
		throw new AssertionError();
	}
}

