package com.shopsignscanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 上传台账（Outbox）：内存队列之外的一层持久化兜底。
 *
 * <p>背景：{@code DatabaseReporter} 的上传队列是 {@code ArrayBlockingQueue(5000)}，
 * 满时旧实现直接丢弃记录（2.2.1 的「队列已满」提示就是这个意思）。
 * 大量扫描 + 网络抖动时，被丢掉的商店要等下一轮全量扫描才会补上，
 * 而全量扫描的间隔由 {@code intervalTicks} 决定——这就是「大量扫描下不完整」的根因之一。
 *
 * <p>本类把每条待上传记录先追加进 {@value #FILE_NAME}，
 * 调用方随后再尝试入内存队列：
 * <ul>
 *   <li>入队成功 → 正常上传；</li>
 *   <li>入队失败（队列满）→ 记录已在磁盘上，下次 {@link #refillFromDisk} 捞回；</li>
 *   <li>进程崩溃 / 客户端重启 → 磁盘上未确认的记录同样由 {@link #refillFromDisk} 捞回。</li>
 * </ul>
 * 上传成功后调用方调 {@link #markDone(long)}，记录随即从「未完成」集合移除，
 * 下一次 {@link #maybeCompact()} 会把已完成的行从文件里清掉。
 *
 * <p><b>身份用序号而不是行号</b>：每一行带一个追加时分配的单调序号（{@code seq}），
 * 文件行号只用于读盘时的定位。压缩重写文件会让行号重排，
 * 若把行号当身份，压缩后 {@code markDone(旧行号)} 会误判别人的完成状态——
 * 早期版本正是因此要在 compact 后把游标「复位」成 keep.size()，
 * 而那一版仍然意味着重启后可能跳过未上传的行。序号不随压缩变化，没有这个问题。
 *
 * <p><b>重复上传是允许的</b>：崩溃后磁盘上可能留有已入库的行，
 * 重启时它们会被重新交付一次。入库用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * （见 {@code DatabaseReporter.INSERT_SQL}），重复执行幂等，没有副作用。
 * 用「可重复」换「不丢失」，这是有意的取舍。
 */
public final class Outbox {
	/** 台账文件名（位于 minecraft 配置目录下） */
	public static final String FILE_NAME = "shopsignscanner-pending.jsonl";

	/** 单行上限：超过说明 raw 异常膨胀，截断后再写（入库时还会按列宽再截一次） */
	private static final int MAX_LINE_BYTES = 8192;
	/** 缓冲行数达到这个值就真正落盘（把「每店一次 IO」折叠成「每 256 店一次」） */
	private static final int BUFFER_FLUSH_LINES = 256;
	/** 缓冲驻留超过这个毫秒数也落盘（保证崩溃窗口有界） */
	private static final long BUFFER_FLUSH_MS = 200L;
	/** compact 最小间隔：同一会话里最多 30 秒压一次 */
	private static final long MIN_COMPACT_INTERVAL_MS = 30_000L;
	/** 已完成行数达到未完成行数这个倍数时才 compact（即至少一半已完成） */
	private static final long COMPACT_DONE_RATIO = 1L;

	private final Path file;
	private final Object writeLock = new Object();

	/** 追加序号分配器；只增不减，压缩也不复位（它是身份，不是行号） */
	private long lastAppendedSeq;
	/** 已追加但尚未确认入库的记录：seq → 行。读盘补货时也会往里放 */
	private final Map<Long, PendingRow> pending = new LinkedHashMap<>();
	/**
	 * 本次进程已经通过 {@link #refillFromDisk} 交付过的序号。
	 *
	 * <p>判重必须按「进程内交付历史」而不是「对象在不在 pending 里」：
	 * 队列溢出时 append 出来的记录本来就在 pending 里，却恰恰是需要回捞的那条。
	 */
	private final java.util.Set<Long> deliveredSeqs = new java.util.HashSet<>();
	/** 自上一次 compact 以来确认入库的条数，用于判断压缩是否划算 */
	private long doneSinceCompact;

	/**
	 * 磁盘上是否可能还有本进程没交付过的行。
	 *
	 * <p>用于跳过无意义的全文读取：正常运行时记录秒入库、{@link #markDone} 秒被调用，
	 * 这个标志很快变 false，{@link #refillFromDisk} 一次文件都不读。
	 * 构造时由文件是否存在决定——有台账就说明上次进程可能留下了未入库记录。
	 */
	private boolean diskMayHaveRows;

	/**
	 * 本次进程是否已经成功往磁盘写过一行。
	 *
	 * <p>false 时 {@link #append} 的第一行<b>不缓冲、立即落盘</b>：目录不可写、
	 * 磁盘满这类问题要在最早的时刻暴露出来，而不是等缓冲满 256 行才发现——
	 * 那时同一缓冲窗里的记录已经既没落盘、又被调用方当成「有磁盘兜底」处理了。
	 * 探测只花一次 IO，之后就走缓冲。
	 */
	private boolean diskVerified;

	/** 待落盘缓冲（见 {@link #BUFFER_FLUSH_LINES}） */
	private final StringBuilder buffer = new StringBuilder(512);
	private int bufferedLines;
	private long firstBufferedAt;

	/** 落盘不可用（权限/磁盘满等）后置位，只告警一次，后续只走内存队列 */
	private volatile boolean broken;
	private long appendFailures;
	/** 读到的无法解析的行数（诊断用；坏行跳过，不堵管线） */
	private long skippedLines;
	private long lastCompactAt;

	public Outbox(Path configDir) {
		this.file = configDir.resolve(FILE_NAME);
		this.diskMayHaveRows = Files.exists(file);
	}

	// -----------------------------------------------------------------
	// 追加 / 确认
	// -----------------------------------------------------------------

	/**
	 * 追加一条待上传记录，返回它的序号（后续 {@link #markDone} 用）。
	 *
	 * <p>写入先进缓冲，缓冲行数达到 {@link #BUFFER_FLUSH_LINES} 或驻留
	 * {@link #BUFFER_FLUSH_MS} 毫秒才真正落盘，上传线程每批处理前也会
	 * {@link #flushNow()}。极端情况下（崩溃）最多丢最后一个缓冲窗口的记录，
	 * 而进程被杀时 OS 仍持有已落盘内容。
	 *
	 * <p><b>首行会立刻落盘</b>（探测磁盘可写性），此后才走缓冲，见 {@link #diskVerified}。
	 *
	 * <p><b>调用时机必须在入内存队列之前</b>：先保证「记录已在磁盘上」，
	 * 再去赌队列有没有位置。
	 *
	 * @return 序号（≥1）；落盘被禁用后返回 -1（表示只走了内存队列）
	 */
	public long append(PendingRow row) {
		if (broken) return -1L;
		synchronized (writeLock) {
			try {
				// 先分配序号再序列化：line() 会把序号写进行首，顺序颠倒会让
				// 落盘行全部带 0 号（自己踩过：三条记录在盘上共用一个 0 号）。
				long seq = ++lastAppendedSeq;
				row.seq = seq;
				byte[] bytes = (row.line() + "\n").getBytes(StandardCharsets.UTF_8);
				if (bytes.length > MAX_LINE_BYTES) {
					// 单行异常膨胀：截断到上限再落。截断只会让这一行入库时按列宽
					// 再截一次（见 DatabaseReporter.insert 的 clamp），不会写坏文件结构。
					bytes = new String(bytes, 0, MAX_LINE_BYTES, StandardCharsets.UTF_8)
							.concat("\n").getBytes(StandardCharsets.UTF_8);
				}
				pending.put(seq, row);
				diskMayHaveRows = true;
				buffer.append(new String(bytes, StandardCharsets.UTF_8));
				bufferedLines++;
				if (firstBufferedAt == 0L) firstBufferedAt = System.currentTimeMillis();
				maybeFlushLocked();
				return seq;
			} catch (Exception e) {
				disableDisk(e);
				return -1L;
			}
		}
	}

	/**
	 * 确认序号 {@code seq} 对应的记录已成功入库，把它移出「未完成」集合。
	 *
	 * <p>序号是身份，不会因为压缩重排而失效，因此重复调用、乱序调用都安全。
	 */
	public void markDone(long seq) {
		if (seq <= 0L) return;
		synchronized (writeLock) {
			if (pending.remove(seq) != null) doneSinceCompact++;
		}
	}

	/** 尚未确认入库的记录条数（含已交付调用方但还没回来的） */
	public int pendingCount() {
		synchronized (writeLock) {
			return pending.size();
		}
	}

	/** 自上一次 compact 以来确认入库的条数 */
	public long doneSinceCompact() {
		synchronized (writeLock) {
			return doneSinceCompact;
		}
	}

	// -----------------------------------------------------------------
	// 落盘
	// -----------------------------------------------------------------

	/** 无条件把缓冲写进磁盘。读盘、压缩、进程退出前都应调用。 */
	public void flushNow() {
		synchronized (writeLock) {
			if (bufferedLines == 0) return;
			try {
				flushLocked();
			} catch (IOException e) {
				disableDisk(e);
			}
		}
	}

	/**
	 * 条件落盘：缓冲行数或驻留时间达到阈值时刷出，否则什么也不做。
	 */
	public void flushIfBuffered() {
		synchronized (writeLock) {
			if (bufferedLines == 0) return;
			if (bufferedLines >= BUFFER_FLUSH_LINES
					|| System.currentTimeMillis() - firstBufferedAt >= BUFFER_FLUSH_MS) {
				try {
					flushLocked();
				} catch (IOException e) {
					disableDisk(e);
				}
			}
		}
	}

	/** 调用方必须已持有 writeLock */
	/**
	 * 缓冲条件落盘：缓冲行数或驻留时间达到阈值时刷出，否则什么也不做。
	 *
	 * <p>首次写入例外——见 {@link #diskVerified}。
	 */
	private void maybeFlushLocked() throws IOException {
		if (bufferedLines == 0) return;
		if (!diskVerified
				|| bufferedLines >= BUFFER_FLUSH_LINES
				|| System.currentTimeMillis() - firstBufferedAt >= BUFFER_FLUSH_MS) {
			flushLocked();
		}
	}

	/**
	 * 把缓冲一次性写进磁盘。
	 *
	 * <p>不调用 force(true)：逐批 fsync 在大批量扫描时会与游戏争磁盘，
	 * 而这里的保命窗口是 OS page cache，进程被杀时数据仍在 OS 层。
	 */
	private void flushLocked() throws IOException {
		if (bufferedLines == 0) return;
		byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
		Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
				StandardOpenOption.WRITE);
		buffer.setLength(0);
		bufferedLines = 0;
		firstBufferedAt = 0L;
		diskVerified = true;
	}

	/** 落盘失败：退化为纯内存队列并只告警一次（队列溢出即丢失，与 2.2.1 行为一致） */
	private void disableDisk(Exception e) {
		broken = true;
		appendFailures++;
		System.err.println("[shopsignscanner] 上传台账落盘失败，已退化为纯内存队列（若队列溢出即丢失）: " + e);
	}

	// -----------------------------------------------------------------
	// 补货：把磁盘上未交付的记录交回调用方
	// -----------------------------------------------------------------

	/**
	 * 把磁盘上尚未交给调用方的未完成记录交给 {@code accept}，最多 {@code max} 条。
	 *
	 * <p>两个入口用途：
	 * <ul>
	 *   <li><b>重启补传</b>：客户端重开后首次调用，磁盘上有多少未确认记录就补多少；</li>
	 *   <li><b>队列满回捞</b>：append 后 offer 失败时，下一轮把磁盘记录重新塞进队列。</li>
	 * </ul>
	 *
	 * <p>判重只看「本次进程有没有把这条交付过」：交付过就跳过，
	 * 没交付过就交付——哪怕这条本身就是本进程 append 出来的（队列溢出时正是这种情况）。
	 *
	 * <p>读的是全文件，但只在 {@link #diskMayHaveRows} 为 true 时才读；
	 * 正常情况下记录秒入库、{@link #markDone} 秒被调用，这个标志很快变 false，因此零开销。
	 *
	 * @return 实际交付条数
	 */
	public long refillFromDisk(Function<PendingRow, Boolean> accept, int max) {
		if (max <= 0) return 0L;
		synchronized (writeLock) {
			flushNow();
			if (!diskMayHaveRows) return 0L;
			if (!Files.isRegularFile(file)) {
				diskMayHaveRows = false;
				return 0L;
			}
			int delivered = 0;
			try {
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size() && delivered < max; i++) {
					String raw = lines.get(i);
					PendingRow row = PendingRow.decode(raw);
					if (row == null) {
						if (!raw.isBlank()) skippedLines++;
						continue; // 坏行跳过，不堵管线
					}
					if (deliveredSeqs.contains(row.seq)) continue; // 本次进程已交付过
					// 先问调用方要不要，再登记"已交付"。
					// 顺序颠倒会丢数据：队列满时被拒的那条一旦进了 deliveredSeqs，
					// 之后队列即使空出来也永远不会再交付（自检抓到的真 bug）。
					if (!accept.apply(row)) return delivered;
					deliveredSeqs.add(row.seq);
					// 没见过的序号（重启场景）也要登记进未完成集合，后续 markDone 才生效
					if (!pending.containsKey(row.seq)) pending.put(row.seq, row);
					delivered++;
				}
				// 扫到文件尾：磁盘上已没有未交付的行
				if (delivered < max) diskMayHaveRows = false;
			} catch (IOException e) {
				// 读失败不等于写失败：不置 broken，下一轮再试
				System.err.println("[shopsignscanner] 读取上传台账失败（本轮不补货，不影响上传）: " + e);
			}
			return delivered;
		}
	}

	// -----------------------------------------------------------------
	// 压缩：把已确认入库的行从文件里清掉
	// -----------------------------------------------------------------

	/**
	 * 已完成行数达到未完成行数 {@value #COMPACT_DONE_RATIO} 倍时重写文件，
	 * 只保留 {@link #pending} 里的序号对应的行。
	 *
	 * <p>保留条件用序号判定，与文件行号无关，因此重写后序号语义不变，
	 * 已在内存队列里的在途记录也不会被误删。
	 *
	 * @return {压缩前行数, 压缩后行数}；返回 null 表示本次未执行（无完成记录、不划算、或重写失败）
	 */
	public long[] compactIfNeeded() {
		synchronized (writeLock) {
			if (pending.isEmpty()) {
				if (doneSinceCompact == 0) return null;
				// 全部完成：直接删文件而不是写空文件，少一次写
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					System.err.println("[shopsignscanner] 上传台账清理失败（下次重试，不影响上传）: " + e);
					return null;
				}
				doneSinceCompact = 0;
				diskMayHaveRows = false;
				return new long[]{0, 0};
			}
			if (doneSinceCompact < pending.size() * COMPACT_DONE_RATIO) return null;
			List<String> keep = new ArrayList<>();
			long before = 0;
			try {
				flushNow();
				if (!Files.isRegularFile(file)) return null;
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				before = lines.size();
				for (String line : lines) {
					if (line.isBlank()) continue;
					PendingRow row = PendingRow.decode(line);
					if (row == null) continue;
					if (pending.containsKey(row.seq)) keep.add(line);
				}
			} catch (IOException e) {
				System.err.println("[shopsignscanner] 上传台账压缩失败（下次重试，不影响上传）: " + e);
				return null;
			}
			if (keep.isEmpty()) return null;
			if (keep.size() >= before) return null; // 没有可压缩空间
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				Files.write(tmp, String.join("\n", keep).concat("\n").getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
				}
				System.err.println("[shopsignscanner] 上传台账压缩失败（下次重试，不影响上传）: " + e);
				return null;
			}
			doneSinceCompact = 0;
			return new long[]{before, keep.size()};
		}
	}

	/** 带时间节流的 compact：频繁扫描时同一次会话里最多 30 秒压一次 */
	public long[] maybeCompact() {
		long now = System.currentTimeMillis();
		if (lastCompactAt != 0 && now - lastCompactAt < MIN_COMPACT_INTERVAL_MS) return null;
		long[] result = compactIfNeeded();
		if (result != null) lastCompactAt = now;
		return result;
	}

	// -----------------------------------------------------------------
	// 单行表示
	// -----------------------------------------------------------------

	/**
	 * 台账里的一行（未完成任务）。
	 *
	 * <p>序列化成 JSON 而不是复用 {@code ShopRecord} 的 pipe 行：
	 * raw 字段是四行牌面拼接，内部本身就可能含 {@code |}、引号、换行，
	 * pipe 行在旧版 txt 里靠「替换 | 为 /」规避，落盘台账要的是<b>无损</b>。
	 *
	 * <p>第 0 个字段是追加序号（JSON 数字），随后 16 个字段严格对应
	 * {@code ShopRecord} 的 16 个分量（见 {@code ShopRecord.HEADER}）。
	 */
	public static final class PendingRow {
		/** 不含序号的字段数（ShopRecord 的 16 个分量） */
		public static final int FIELD_COUNT = 16;

		private final String[] fields = new String[FIELD_COUNT];
		/** 追加序号（身份，不随压缩变化）；0 表示尚未 append */
		private long seq;

		/**
		 * 落盘用的那一个版本（带行首序号）。
		 * 只有 {@link Outbox#append} 内部会去调，它保证先设好序号再取这行。
		 */
		private String line() {
			StringBuilder sb = new StringBuilder(160);
			sb.append('[').append(seq);
			for (int i = 0; i < FIELD_COUNT; i++) {
				sb.append(',');
				appendJsonString(sb, fields[i]);
			}
			return sb.append(']').toString();
		}

		PendingRow(String[] f) {
			if (f == null || f.length != FIELD_COUNT) {
				throw new IllegalArgumentException("字段数必须是 " + FIELD_COUNT + "，实际 "
						+ (f == null ? "null" : f.length));
			}
			System.arraycopy(f, 0, this.fields, 0, FIELD_COUNT);
			for (int i = 0; i < FIELD_COUNT; i++) {
				if (this.fields[i] == null) this.fields[i] = "";
			}
		}

		/** 正常构造：字段数不是 16 视为编程错误，直接抛 */
		public static PendingRow of(String[] f) {
			return new PendingRow(f);
		}

		/** 追加序号 */
		public long seq() {
			return seq;
		}

		public String encode() {
			StringBuilder sb = new StringBuilder(160);
			sb.append('[').append(seq);
			for (int i = 0; i < FIELD_COUNT; i++) {
				sb.append(',');
				appendJsonString(sb, fields[i]);
			}
			return sb.append(']').toString();
		}

		/**
		 * 解析一行 JSON 数组；失败返回 null（调用方跳过而不是抛，一行坏数据不能堵死管线）。
		 */
		public static PendingRow decode(String line) {
			if (line == null || line.isEmpty() || line.charAt(0) != '[') return null;
			int close = line.lastIndexOf(']');
			if (close <= 0) return null;
			String inner = line.substring(1, close);
			List<String> values = new ArrayList<>(FIELD_COUNT + 1);
			int i = 0;
			// 第一个字段是序号：纯数字，不带引号
			int numStart = i;
			while (i < inner.length() && Character.isDigit(inner.charAt(i))) i++;
			if (i == numStart) return null;
			values.add(inner.substring(numStart, i));
			// 其余 16 个字段是带引号的 JSON 字符串
			while (i < inner.length()) {
				if (inner.charAt(i) != ',') return null;
				i++;
				if (i >= inner.length() || inner.charAt(i) != '"') return null;
				i++;
				StringBuilder sb = new StringBuilder();
				while (i < inner.length() && inner.charAt(i) != '"') {
					char c = inner.charAt(i);
					if (c == '\\' && i + 1 < inner.length()) {
						char n = inner.charAt(++i);
						switch (n) {
							case 'n' -> sb.append('\n');
							case 't' -> sb.append('\t');
							case 'r' -> sb.append('\r');
							case 'u' -> {
								if (i + 4 >= inner.length()) return null;
								try {
									sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
								} catch (NumberFormatException e) {
									return null;
								}
								i += 4;
							}
							default -> sb.append(n);
						}
						i++;
					} else {
						sb.append(c);
						i++;
					}
				}
				if (i >= inner.length()) return null;
				i++; // 跳过收尾引号
				values.add(sb.toString());
			}
			if (values.size() != FIELD_COUNT + 1) return null;
			String[] f = values.subList(1, values.size()).toArray(new String[0]);
			PendingRow row = new PendingRow(f);
			try {
				row.seq = Long.parseLong(values.get(0));
			} catch (NumberFormatException e) {
				return null;
			}
			return row;
		}

		public String get(int idx) {
			return fields[idx];
		}
	}

	/** JSON 字符串转义（只做必须的几种：保证能无损 round-trip 回 ShopRecord 字段） */
	private static void appendJsonString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\t' -> sb.append("\\t");
				case '\r' -> sb.append("\\r");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// -----------------------------------------------------------------
	// 状态
	// -----------------------------------------------------------------

	/** 台账路径（测试与排障用） */
	public Path path() {
		return file;
	}

	/** 落盘是否已被禁用（权限/磁盘异常） */
	public boolean isBroken() {
		return broken;
	}

	/** 落盘失败次数（自禁用后只告警一次，因此最多 1） */
	public long appendFailures() {
		return appendFailures;
	}

	/** 读盘时跳过的坏行数 */
	public long skippedLines() {
		return skippedLines;
	}

	/** 台账当前统计（测试与排障用） */
	public String debugState() {
		synchronized (writeLock) {
			return "seq=" + lastAppendedSeq + ", pending=" + pending.size()
					+ ", done=" + doneSinceCompact + ", disk=" + diskMayHaveRows
					+ ", buffered=" + bufferedLines + ", broken=" + broken;
		}
	}
}
