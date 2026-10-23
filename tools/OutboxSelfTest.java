package com.shopsignscanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbox 的纯 JVM 自检（无 Minecraft 依赖，直接 java 运行）。
 *
 * <p>把要验证的逻辑放在不依赖 Fabric/Minecraft/JDBC 的类里，
 * 就能在没法跑 gradle 的机器上用单文件 javac + main 自检。
 * Outbox 是上传完整性的核心，值得一份自检。
 */
public final class OutboxSelfTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		Path dir = Files.createTempDirectory("outbox-selftest");
		try {
			testAppendAssignsIncreasingSeqs(dir);
			testBufferDefersDiskIo(dir);
			testRoundTripSpecialCharacters(dir);
			testMarkDoneThenCompact(dir);
			testRestartFlowRefillsFromDisk(dir);
			testCorruptLineIsSkipped(dir);
			testBrokenWhenDirUnwritable(dir);
			testRefillStopsWhenQueueFull(dir);
			testRefillSkipsAlreadyOffered(dir);
			testCorruptAndUndoneMixedRefill(dir);
		} finally {
			deleteRecursively(dir);
		}

		System.out.println("OutboxSelfTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}

	// -----------------------------------------------------------------
	// 用例
	// -----------------------------------------------------------------

	private static void testAppendAssignsIncreasingSeqs(Path dir) throws Exception {
		Path sub = dir.resolve("ids");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		long a = box.append(row("a"));
		long b = box.append(row("b"));
		long c = box.append(row("c"));
		check("序号递增且从 1 开始", a == 1 && b == 2 && c == 3, "a=" + a + " b=" + b + " c=" + c);
		check("pending 记录未确认条数", box.pendingCount() == 3, box.debugState());
		// refill 前会先无条件刷盘，所以小批追加也能立刻被读到
		check("小批追加即可被补货（refill 自带刷盘）", box.refillFromDisk(r -> true, 100) == 3,
				box.debugState());
	}

	private static void testBufferDefersDiskIo(Path dir) throws Exception {
		Path sub = dir.resolve("buffer");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		for (int i = 0; i < 300; i++) box.append(row("b" + i));
		// 超过 BUFFER_FLUSH_LINES 后应已自动刷出磁盘
		check("超过缓冲行数即自动落盘", Files.isRegularFile(box.path()), box.debugState());
		long n = box.refillFromDisk(r -> true, 1000);
		check("落盘后可补货全部 300 条", n == 300, "n=" + n);
	}

	private static void testRoundTripSpecialCharacters(Path dir) throws Exception {
		Path sub = dir.resolve("rt");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		// raw 字段含牌面四行拼接：引号、反斜杠、竖线、换行全都要无损
		String raw = "店主\"王\"\n物品|钻石\n价格\\1000";
		String landmark = "领地名\t带制表符";
		String[] f = new String[]{
				"2026-10-20 12:00:00", "owner\"x\"", "sell", "-1", "diamond|sword", "1000.5", "1,000.5",
				"minecraft:overworld", "10", "20", "30", "front", "play.example.com", raw, landmark, "港口 A · 120m"
		};
		long seq = box.append(Outbox.PendingRow.of(f));
		box.flushIfBuffered();
		var rows = collect(box);
		check("补货一条", rows.size() == 1, "rows=" + rows.size());
		Outbox.PendingRow r = rows.get(0);
		check("raw 无损", raw.equals(r.get(13)), "got=" + r.get(13));
		check("land 含制表符无损", landmark.equals(r.get(14)), "got=" + r.get(14));
		check("owner 引号无损", "owner\"x\"".equals(r.get(1)), "got=" + r.get(1));
		check("序号无损", r.seq() == seq && seq == 1, "seq=" + r.seq());
	}

	private static void testMarkDoneThenCompact(Path dir) throws Exception {
		Path sub = dir.resolve("compact");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		List<Long> seqs = new ArrayList<>();
		for (int i = 0; i < 10; i++) seqs.add(box.append(row("r" + i)));
		box.flushIfBuffered();
		var rows = collect(box);
		check("先补货 10 行", rows.size() == 10, "rows=" + rows.size());
		// 入库成功 8 条
		for (int i = 0; i < 8; i++) box.markDone(seqs.get(i));
		check("pending 剩 2 条", box.pendingCount() == 2, box.debugState());
		long[] res = box.compactIfNeeded();
		check("压缩执行", res != null && res[0] == 10 && res[1] == 2,
				res == null ? "null" : (res[0] + "->" + res[1]));
		var lines = Files.readAllLines(box.path(), StandardCharsets.UTF_8);
		check("文件只剩未完成行", lines.size() == 2, "lines=" + lines.size());
		// 序号身份不随压缩变化：压缩后文件里剩下的仍是序号 9、10 那两行
		check("压缩后保留的是 9、10 两行",
				lines.get(0).startsWith("[9,") && lines.get(1).startsWith("[10,"),
				"lines=" + String.join(" | ", lines));
		// 这两行本进程已经交付过，refill 不会重复给；重启（新实例）才会重新补
		var rows2 = collect(box);
		check("已交付的行压缩后不重复补", rows2.isEmpty(), "rows=" + rows2.size());
		Outbox afterRestart = new Outbox(sub);
		var rows3 = collect(afterRestart);
		check("重启后压缩保留的未完成行能补回", rows3.size() == 2, "rows=" + rows3.size());
		check("补回的序号仍是 9 和 10",
				rows3.get(0).seq() == 9 && rows3.get(1).seq() == 10,
				"seqs=" + rows3.get(0).seq() + "," + rows3.get(1).seq());
		box.markDone(9);
		box.markDone(10);
		check("全部确认后 pending 清空", box.pendingCount() == 0, box.debugState());
		res = box.compactIfNeeded();
		check("全部完成时删除台账", res != null && !Files.exists(box.path()),
				res == null ? "null" : (res[0] + "->" + res[1]));
	}

	private static void testRestartFlowRefillsFromDisk(Path dir) throws Exception {
		// 模拟：崩溃前 append 了 6 行，其中 2 行已成功入库，其余未入库。
		// 重启后必须把剩下 4 行全部补货（这是 Outbox 要保住的场景）。
		Path sub = dir.resolve("restart");
		Files.createDirectories(sub);
		Outbox first = new Outbox(sub);
		List<Long> seqs = new ArrayList<>();
		for (int i = 0; i < 6; i++) seqs.add(first.append(row("s" + i)));
		first.flushNow();
		first.markDone(seqs.get(0));
		first.markDone(seqs.get(1));
		// 崩溃：丢弃 first 实例（内存态全部丢失）
		Outbox second = new Outbox(sub);
		var rows = collect(second);
		check("重启后补货全部 6 行（重复入库由 ON DUPLICATE KEY 兜底）", rows.size() == 6,
				"rows=" + rows.size());
		check("补货序号从 1 到 6",
				rows.get(0).seq() == 1 && rows.get(5).seq() == 6,
				"seqs=" + rows.get(0).seq() + ".." + rows.get(5).seq());
	}

	private static void testCorruptLineIsSkipped(Path dir) throws Exception {
		Path sub = dir.resolve("corrupt");
		Files.createDirectories(sub);
		Files.writeString(sub.resolve(Outbox.FILE_NAME),
				line(1, "good1") + "\n"
						+ "[[[坏行\n"
						+ "[\"字段数不对\"]\n"
						+ "\n"
						+ line(5, "good2") + "\n",
				StandardCharsets.UTF_8);
		Outbox box = new Outbox(sub);
		var rows = collect(box, 100);
		check("坏行被跳过，好行保留", rows.size() == 2, "rows=" + rows.size());
		check("序号按行首字段给", rows.get(0).seq() == 1 && rows.get(1).seq() == 5,
				"seqs=" + rows.get(0).seq() + "," + rows.get(1).seq());
		check("坏行计数", box.skippedLines() == 2, "skipped=" + box.skippedLines());
	}

	private static void testBrokenWhenDirUnwritable(Path dir) throws Exception {
		// 写入一个不可能存在的位置（父路径是文件）→ append 失败后自禁用，不抛
		Path asFile = dir.resolve("not-a-dir");
		Files.createDirectories(asFile);
		Outbox box = new Outbox(asFile.resolve("x").resolve("y"));
		check("写失败返回 -1", box.append(row("r")) == -1, "id=" + box.debugState());
		check("标记为 broken", box.isBroken(), "broken=" + box.isBroken());
		check("只告警一次（失败计数=1）", box.appendFailures() == 1, "n=" + box.appendFailures());
		check("再次 append 仍返回 -1", box.append(row("r3")) == -1, "");
		check("appendFailures 仍为 1", box.appendFailures() == 1, "n=" + box.appendFailures());
	}

	private static void testRefillStopsWhenQueueFull(Path dir) throws Exception {
		Path sub = dir.resolve("queuefull");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		for (int i = 0; i < 10; i++) box.append(row("q" + i));
		box.flushIfBuffered();
		// 内存队列只收 3 条：第三次 accept 返回 false，补货必须立刻停
		AtomicInteger accepted = new AtomicInteger();
		long delivered = box.refillFromDisk(r -> accepted.incrementAndGet() <= 3, 100);
		check("队列满时只补货 3 条", delivered == 3, "delivered=" + delivered);
		var rows = collect(box, 100);
		// collect 会反复补货直到拿空：第一轮停下的那 6 条会被后续轮次收走，
		// 加上第一轮队列里已收的 3 条（那条 q3 本就在 collect 之外交付过，
		// 但 collect 从空列表开始，所以只统计 collect 自己收的 7 条）
		check("被队列堵住的行下一轮还能拿到", rows.size() == 7, "rows=" + rows.size());
		check("补货从断点继续（序号 4 起）", rows.get(0).seq() == 4, "seq=" + rows.get(0).seq());
		check("断点续传拿到最后一条（序号 10）", rows.get(6).seq() == 10,
				"seq=" + rows.get(6).seq());
		// 不重 append，再补一次应该拿空
		long more = box.refillFromDisk(r -> true, 100);
		check("拿完后不再重复交付", more == 0, "more=" + more);
		check("全部交付后再补为 0", box.refillFromDisk(r -> true, 100) == 0, box.debugState());
	}

	private static void testRefillSkipsAlreadyOffered(Path dir) throws Exception {
		Path sub = dir.resolve("nooffer");
		Files.createDirectories(sub);
		Outbox box = new Outbox(sub);
		for (int i = 0; i < 4; i++) box.append(row("n" + i));
		box.flushIfBuffered();
		var rows = collect(box, 100);
		check("全部交付", rows.size() == 4, "rows=" + rows.size());
		var again = collect(box, 100);
		check("已交付的行不会重复补", again.isEmpty(), "rows=" + again.size());
	}

	private static void testCorruptAndUndoneMixedRefill(Path dir) throws Exception {
		// 磁盘同时有坏行与未完成行：坏行跳过，未完成行不漏
		Path sub = dir.resolve("mixed");
		Files.createDirectories(sub);
		Files.writeString(sub.resolve(Outbox.FILE_NAME),
				"[\"字段数不对\"]\n"
						+ line(2, "m1") + "\n"
						+ "损坏\n"
						+ line(4, "m2") + "\n",
				StandardCharsets.UTF_8);
		Outbox box = new Outbox(sub);
		var rows = collect(box, 100);
		check("坏行不堵管线，好行照样补", rows.size() == 2, "rows=" + rows.size());
		check("行号保持为文件位置",
				rows.get(0).seq() == 2 && rows.get(1).seq() == 4,
				"seqs=" + rows.get(0).seq() + "," + rows.get(1).seq());
	}

	// -----------------------------------------------------------------
	// 辅助
	// -----------------------------------------------------------------

	/** 反复补货直到拿空或达到上限，收集全部交付行 */
	private static List<Outbox.PendingRow> collect(Outbox box, int max) {
		List<Outbox.PendingRow> out = new ArrayList<>();
		for (int i = 0; i < max; i++) {
			AtomicInteger step = new AtomicInteger();
			long n = box.refillFromDisk(r -> {
				step.set(1);
				out.add(r);
				return true;
			}, 100);
			if (n == 0) break;
		}
		return out;
	}

	private static List<Outbox.PendingRow> collect(Outbox box) {
		return collect(box, 50);
	}

	static String[] fieldsForProbe(String tag) {
		return fields(tag);
	}

	private static String[] fields(String tag) {
		return new String[]{
				"2026-10-20 12:00:00", tag, "sell", "-1", "diamond", "10", "10",
				"minecraft:overworld", "1", "2", "3", "front", "play.example.com",
				"a|b|c|d", "", "港口"
		};
	}

	private static Outbox.PendingRow row(String tag) {
		return Outbox.PendingRow.of(fields(tag));
	}

	/** 手写一行带指定序号的台账行（模拟别处写出的文件） */
	private static String line(long seq, String tag) {
		Outbox.PendingRow r = Outbox.PendingRow.of(fields(tag));
		java.lang.reflect.Field f;
		try {
			f = Outbox.PendingRow.class.getDeclaredField("seq");
			f.setAccessible(true);
			f.setLong(r, seq);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		return r.encode();
	}

	private static void check(String name, boolean ok, String detail) {
		if (ok) {
			passed++;
			System.out.println("[OK]   " + name);
		} else {
			failed++;
			System.out.println("[FAIL] " + name + (detail.isEmpty() ? "" : " ｜ " + detail));
		}
	}

	private static void deleteRecursively(Path p) throws IOException {
		if (Files.isDirectory(p)) {
			try (var s = Files.list(p)) {
				for (Path c : s.toList()) deleteRecursively(c);
			}
		}
		Files.deleteIfExists(p);
	}
}
