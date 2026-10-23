package com.shopsign.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 一条商店告示牌记录。txt 每行一个记录，字段用 | 分隔；
 * 文本字段中的 | 会被替换为 /，保证一行固定 16 列（land/gateway 为 15/16 列，早期版本无）。
 */
public record ShopRecord(
		String time,
		String owner,
		String type,
		int quantity,
		String item,
		double price,
		String priceRaw,
		String world,
		int x,
		int y,
		int z,
		String side,
		String server,
		String raw,
		String land,
		String gateway) {

	public static final String HEADER =
			"time|owner|type|quantity|item|price|price_raw|world|x|y|z|side|server|raw|land|gateway";

	/**
	 * 位置键：同一服务器 + 同一世界 + 同一坐标 + 同一面，视为同一个商店。
	 *
	 * <p><b>v2.0.0 修复</b>：原实现是 <code>world|x,y,z|side</code>，<em>不含 server</em>。
	 * 两个不同服务器上的同名世界（几乎都是 <code>minecraft:overworld</code>）
	 * 同坐标同面会算出同一个 location_key，ON DUPLICATE KEY UPDATE 会让它们互相覆盖。
	 * <b>注意</b>：land/gateway 只是对数据的描述，不参与键计算 ——
	 * 领地改名/换最近港口不会把同一商店变成两行。
	 * 拼接方式必须与 MySQL 迁移里的
	 * <code>MD5(CONCAT(server,'|',world,'|',x,',',y,',',z,'|',side))</code> 完全一致。
	 */
	public String locationKey() {
		return server + "|" + world + "|" + x + "," + y + "," + z + "|" + side;
	}

	/** 位置键的 MD5（本地去重集与 MySQL location_key 列统一使用） */
	public String locationKeyMd5() {
		return md5(locationKey());
	}

	public static String md5(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(32);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public String toLine() {
		return time + "|" + owner + "|" + type + "|" + quantity + "|" + item + "|" + price
				+ "|" + priceRaw + "|" + world + "|" + x + "|" + y + "|" + z
				+ "|" + side + "|" + server + "|" + raw + "|" + land + "|" + gateway;
	}

	/**
	 * 还原成上传台账的 16 个字段（顺序必须与 {@link #toOutboxFields} 一致）。
	 *
	 * <p>台账是本地中间态，字段全都能无损 round-trip：
	 * 已截断过的值截断后还是一样。数量/坐标按 {@code Long.toString} 写回。
	 */
	public static ShopRecord fromOutboxFields(com.shopsignscanner.Outbox.PendingRow row) {
		try {
			return new ShopRecord(
					row.get(0), row.get(1), row.get(2), Integer.parseInt(row.get(3)),
					row.get(4), Double.parseDouble(row.get(5)), row.get(6), row.get(7),
					Integer.parseInt(row.get(8)), Integer.parseInt(row.get(9)),
					Integer.parseInt(row.get(10)), row.get(11), row.get(12),
					row.get(13), row.get(14), row.get(15));
		} catch (RuntimeException e) {
			// decode 已保证字段数是 16，能走到这里只可能是数字字段被写坏了
			System.err.println("[shopsignscanner] 上传台账行解析失败（跳过该行）: " + e);
			return null;
		}
	}

	/** 序列化成上传台账的 16 个字段 */
	public String[] toOutboxFields() {
		return new String[]{
				time, owner, type, Integer.toString(quantity), item,
				Double.toString(price), priceRaw, world,
				Integer.toString(x), Integer.toString(y), Integer.toString(z),
				side, server, raw, land, gateway
		};
	}

	/** txt 列分隔符。LITERAL 预编译：String.split 每次调用都要重新编译正则，是无谓开销 */
	private static final Pattern PIPE = Pattern.compile("|", Pattern.LITERAL);

	/**
	 * 从 txt 行解析出完整记录（供商店查询面板使用）。
	 * 兼容 13/14/15/16 列四代格式；13 列历史行没有 type 列，置为空串。
	 * 表头或异常行返回 null。
	 */
	public static ShopRecord fromLine(String line) {
		if (line == null) return null;
		String[] f = PIPE.split(line, -1);
		if ((f.length != 13 && f.length != 14 && f.length != 15 && f.length != 16) || "time".equals(f[0])) return null;
		try {
			String time = f[0];
			String owner = f[1];
			String type;
			int quantity;
			String item;
			double price;
			String priceRaw;
			String world;
			int x, y, z;
			String side;
			String server;
			String raw;
			String land = "";
			String gateway = "";
			switch (f.length) {
				case 13 -> {
					type = "";
					quantity = Integer.parseInt(f[2]);
					item = f[3];
					price = Double.parseDouble(f[4]);
					priceRaw = f[5];
					world = f[6];
					x = Integer.parseInt(f[7]);
					y = Integer.parseInt(f[8]);
					z = Integer.parseInt(f[9]);
					side = f[10];
					server = f[11];
					raw = f[12];
				}
				default -> {
					type = f[2];
					quantity = Integer.parseInt(f[3]);
					item = f[4];
					price = Double.parseDouble(f[5]);
					priceRaw = f[6];
					world = f[7];
					x = Integer.parseInt(f[8]);
					y = Integer.parseInt(f[9]);
					z = Integer.parseInt(f[10]);
					side = f[11];
					server = f[12];
					raw = f[13];
					if (f.length >= 15) land = f[14];
					if (f.length == 16) gateway = f[15];
				}
			}
			return new ShopRecord(time, owner, type, quantity, item, price, priceRaw,
					world, x, y, z, side, server, raw, land, gateway);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 从 txt 行解析出 {位置键MD5, raw, land, gateway}；
	 * 兼容 13/14（无 land/gateway）、15（无 gateway）、16 列格式，表头或异常行返回 null。
	 *
	 * <p>键的算法必须与 {@link #locationKey()} 一致（含 server），
	 * 否则客户端会认为所有商店都是新的、重复记录一遍。
	 */
	public static String[] locationOfLine(String line) {
		if (line == null) return null;
		String[] f = PIPE.split(line, -1);
		if ((f.length != 13 && f.length != 14 && f.length != 15 && f.length != 16) || "time".equals(f[0])) return null;
		String raw;
		String land = "";
		String gateway = "";
		switch (f.length) {
			case 13 -> { raw = f[12]; }
			case 14 -> { raw = f[13]; }
			case 15 -> { raw = f[13]; land = f[14]; }
			default -> { raw = f[13]; land = f[14]; gateway = f[15]; }
		}
		// 13 列：world=6..side=10, server=11；14 列起整体后移 1：world=7..side=11, server=12
		int worldIdx = f.length == 13 ? 6 : 7;
		int serverIdx = f.length == 13 ? 11 : 12;
		String key = md5(f[serverIdx] + "|" + f[worldIdx] + "|"
				+ f[worldIdx + 1] + "," + f[worldIdx + 2] + "," + f[worldIdx + 3] + "|" + f[worldIdx + 4]);
		return new String[]{key, raw, land, gateway};
	}
}
