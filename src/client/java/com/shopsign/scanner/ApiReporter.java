package com.shopsign.scanner;

import java.util.ArrayList;
import java.util.List;

import com.shopsignscanner.ApiClient;
import com.shopsignscanner.ApiModels;

/**
 * api 模式的上报出口：把一批 {@link ShopRecord} POST 到商店网站的 ingest 接口。
 *
 * <h2>为什么它只是 DatabaseReporter 的一个「投递」实现</h2>
 * Outbox（磁盘台账）、内存队列、{@code markSeen} 的心跳收集、上传节流、
 * 指数退避、{@code statusText} —— 这些行为在 api 与 direct 两种模式下必须<b>完全一致</b>，
 * 否则「换模式后上传变慢/丢记录/状态不刷新」会变成无法归因的玄学问题。
 * 因此本类<b>只</b>负责「把这条批交出去」：
 * {@code DatabaseReporter} 内部把它包成私有接口 {@code Deliverer} 的一个实现，
 * {@code workerLoop}/{@code drainBatch} 的主体一行不改。
 *
 * <h2>心跳</h2>
 * 用户已确认「心跳并进普通 upsert 批量」：一次 POST 的 body 里既有 shops 也有
 * heartbeatKeys，站点对后者做 touchLastSeen。因此这里不维护自己的心跳定时器 ——
 * 攒 key 的 {@code pendingHeartbeat} 在 {@code DatabaseReporter} 里，随批取出。
 *
 * <h2>错误语义</h2>
 * {@link ApiClient} 不抛异常，任何失败都以 {@code IngestResponse(ok=false, reason)}
 * 表达。本类把它翻译成 {@link DatabaseReporter} 的分拣逻辑能理解的东西：
 * <ul>
 *   <li>整体失败 → 抛 {@link ApiDeliveryException}（RuntimeException），
 *       让 drainBatch 走「回队 + 退避重试」；</li>
 *   <li>{@code rejected > 0} → 站点逐条拒了部分商店（字段不合法），
 *       已成功的那部分仍要确认台账，因此正常返回；</li>
 *   <li>reason={@code data}（价格越界）→ 同样是
 *       {@link ApiDeliveryException}，但 DatabaseReporter 按 reason 分拣为
 *       「重试也没用，丢弃该条」，与 direct 模式下 {@code DbErrors.isDataError}
 *       对 {@code SQLDataException} 的处理对齐。</li>
 * </ul>
 * 抛出的消息会原样进入 statusText，所以每条都带排查动作。
 *
 * @see ApiClient 错误分诊与超时策略
 * @see ApiModels 请求体/响应体编解码
 */
public final class ApiReporter {

	private final ApiClient client;
	private final String version;
	private final String serverName;

	/**
	 * @param apiBaseUrl 站点根地址
	 * @param apiSecret  与站点 INGEST_SHARED_SECRET 一致的共享密钥
	 * @param version    mod 版本（写入请求体 client.version）
	 * @param serverName 当前服务器标识（单机为 "singleplayer"）
	 * @param log        日志出口，可为 null
	 */
	public ApiReporter(String apiBaseUrl, String apiSecret, String version, String serverName,
			java.util.function.Consumer<String> log) {
		this.client = new ApiClient(apiBaseUrl, apiSecret, log);
		this.version = version;
		this.serverName = serverName;
	}

	/** 站点地址（排障用） */
	public String baseUrl() {
		return client.baseUrl();
	}

	/** 探活：只发 timestamp，用于验证地址与密钥配对 */
	public ApiModels.IngestResponse health() {
		return client.health();
	}

	/**
	 * 投递一批商店 + 随行心跳。
	 *
	 * @param records        本批商店（≤200，由 ApiModels 校验）
	 * @param heartbeatKeys  随本批一起发出的位置键（≤5000），可为空
	 * @return 站点响应（ok=true；rejected>0 也视为已交付）
	 * @throws ApiDeliveryException 网络/鉴权/服务端错误，调用方应回队重试
	 */
	public ApiModels.IngestResponse deliver(List<ShopRecord> records, List<String> heartbeatKeys)
			throws ApiDeliveryException {
		List<ApiModels.ShopPayload> payload = new ArrayList<>(records.size());
		for (ShopRecord r : records) {
			payload.add(toPayload(r));
		}
		ApiModels.IngestResponse response = client.postShops(version, serverName, payload, heartbeatKeys);
		if (!response.ok()) {
			throw new ApiDeliveryException(response);
		}
		return response;
	}

	/**
	 * ShopRecord → 契约字段。
	 *
	 * <p>两个时间列都写 {@code r.time()}：直连路径的 {@code bind()} 也是把同一个
	 * time 写进 seen_at / first_seen_at / last_seen_at，站点侧 first_seen_at
	 * 不进 UPDATE 列表，语义一致。
	 *
	 * <p>价格与非有限值：契约要求 {@code |v| >= 1e15} 由 mod 发送前拦下。
	 * 这里与 {@code bind()} 的判据保持同一常量（{@code ColumnLimits.MAX_PRICE}），
	 * 越界直接抛数据错误，让 drainBatch 丢弃该条而不是发一批非法数字给站点。
	 */
	private static ApiModels.ShopPayload toPayload(ShopRecord r) {
		if (!Double.isFinite(r.price()) || Math.abs(r.price()) >= com.shopsignscanner.ColumnLimits.MAX_PRICE) {
			// 旧设计在这里抛受检的 java.sql.SQLDataException，好让 DatabaseReporter 的
			// DbErrors.isDataError 认出「这是数据问题，重试也没用」。但它是受检异常，
			// 编译不过；改用带 reason="data" 的 ApiDeliveryException 表达同一语义，
			// DatabaseReporter 侧按 reason 分拣，行为与直连模式一致。
			throw new ApiDeliveryException(ApiModels.IngestResponse.failure(
					"data", "价格超出可表示范围: " + r.price() + "（牌面原文: " + r.priceRaw() + "）"));
		}
		String time = r.time() == null ? "" : r.time();
		return new ApiModels.ShopPayload(
				time,
				time,
				nullToEmpty(r.owner()),
				nullToEmpty(r.type()),
				Math.max(0, r.quantity()),
				nullToEmpty(r.item()),
				r.price(),
				nullToEmpty(r.priceRaw()),
				nullToEmpty(r.world()),
				r.x(),
				r.y(),
				r.z(),
				nullToEmpty(r.side()),
				nullToEmpty(r.server()),
				nullToEmpty(r.raw()),
				nullToEmpty(r.land()),
				nullToEmpty(r.gateway()));
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	/**
	 * 投递失败（携带站点响应的 reason 码与可读消息）。
	 *
	 * <p>reason=「data」是本类与 {@code DatabaseReporter} 共用的暗号：
	 * 那里用 {@code DbErrors.isDataError} 判断「重试也没用，直接丢弃」，
	 * 价格越界就走这条路，两种模式的丢弃行为因此完全一致。
	 *
	 * <p>之所以不用 {@code java.sql.SQLDataException}：它是受检异常，
	 * 而这个类在 client 源集、被 api 模式的 Deliverer 调用，签名上声明
	 * {@code throws Exception} 虽然兼容，但会让「纯数据问题」和「网络问题」
	 * 混在同一层。用带 reason 码的运行时异常，drainBatch 只需要看 reason。
	 */
	public static final class ApiDeliveryException extends RuntimeException {
		private final ApiModels.IngestResponse response;

		public ApiDeliveryException(ApiModels.IngestResponse response) {
			super(response.reason() + ": " + response.message());
			this.response = response;
		}

		/** reason 码：config / auth / rate / server / timeout / network / interrupted / parse / data */
		public String reason() {
			return response.reason();
		}

		public String hint() {
			return response.message();
		}
	}
}
