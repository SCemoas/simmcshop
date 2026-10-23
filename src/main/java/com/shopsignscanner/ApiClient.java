package com.shopsignscanner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * 站点 ingest 接口的 HTTP 客户端（纯逻辑：不 import 任何 Fabric / Minecraft 类）。
 *
 * <p>与直连 MySQL 的 {@code direct} 模式相对，{@code api} 模式下 mod 只需要
 * baseUrl + apiSecret 两个值，不需要数据库口令——口令不再随客户端分发。
 *
 * <p><b>签名</b>：{@code X-ShopSign-Signature = HMAC_SHA256(apiSecret, 原始请求体字节)}
 * 的小写 hex。签名必须基于实际发出的字节：本类先用 {@link ApiModels} 拼出请求体字符串，
 * 再取它的 UTF-8 字节同时用于签名和发送，杜绝「序列化两次」导致的验签失败。
 * {@code timestamp} 由 {@link ApiModels} 在拼接时写入 {@link System#currentTimeMillis()}，
 * 站点按 ±5 分钟窗口校验。
 *
 * <p><b>超时</b>：连接 10s、请求 20s。上传 worker 跑在客户端自己的线程上，
 * 超时后由 {@code DatabaseReporter} 的退避逻辑接手重试，这里只负责尽快失败。
 *
 * <p><b>错误分诊</b>（reason 码，供 statusText 与日志直接使用）：
 * <ul>
 *   <li>{@code config} —— apiSecret 为空，根本没发请求；</li>
 *   <li>{@code auth} —— HTTP 401/403，签名或密钥不匹配；</li>
 *   <li>{@code rate} —— HTTP 429，限流桶（600 次/分钟/IP）打满；</li>
 *   <li>{@code server} —— HTTP 5xx 或响应体 {@code ok=false}；</li>
 *   <li>{@code timeout} / {@code network} —— 连接或读取阶段 IO 失败；</li>
 *   <li>{@code interrupted} —— 线程被中断（通常是客户端关闭）；</li>
 *   <li>{@code parse} —— 响应体不是可解析的 JSON。</li>
 * </ul>
 * 每个 reason 对应的 message 都带排查动作，会原样进入 {@code statusText()}。
 */
public final class ApiClient {

    /** 验签头名（与站点 ingestController 约定一致） */
    public static final String SIGNATURE_HEADER = "X-ShopSign-Signature";

    private static final String SHOPS_PATH = "/api/ingest/shops";
    private static final String HEALTH_PATH = "/api/ingest/health";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final String baseUrl;
    private final String apiSecret;
    private final HttpClient http;
    /** 日志出口：纯逻辑层不引用 MC 日志器，由调用方注入（null = 不记日志） */
    private final Consumer<String> log;

    /**
     * @param baseUrl    上报后端根地址，如 {@code https://api.example.com}（末尾斜杠会被去掉）
     * @param apiSecret  共享密钥，与站点 {@code INGEST_SHARED_SECRET} 一致
     * @param log        日志出口，可为 null
     */
    public ApiClient(String baseUrl, String apiSecret, Consumer<String> log) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 站点地址（已去尾斜杠，仅日志/排障用） */
    public String baseUrl() {
        return baseUrl;
    }

    /** 密钥是否已配置（空串视为未配置，直接不發请求） */
    public boolean hasSecret() {
        return !apiSecret.isEmpty();
    }

    // -----------------------------------------------------------------
    // 接口
    // -----------------------------------------------------------------

    /**
     * 上报一批商店 + 随行心跳。
     *
     * @param version       mod 版本
     * @param serverName    当前服务器标识
     * @param shops         商店列表（≤200）
     * @param heartbeatKeys 心跳 key（≤5000），可为空
     * @return 解析后的响应；任何失败都以 ok=false + reason 表达，不抛异常
     */
    public ApiModels.IngestResponse postShops(String version, String serverName,
                                              List<ApiModels.ShopPayload> shops, List<String> heartbeatKeys) {
        if (apiSecret.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config",
                    "未配置 apiSecret：请在配置文件填写与站点 INGEST_SHARED_SECRET 一致的值");
            log(r.reason() + " " + r.message());
            return r;
        }
        if (baseUrl.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config", "未配置 apiBaseUrl");
            log(r.reason() + " " + r.message());
            return r;
        }
        String body;
        try {
            body = ApiModels.buildShopsRequest(version, serverName, shops, heartbeatKeys);
        } catch (RuntimeException e) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config", "请求体构造失败：" + e.getMessage());
            log(r.reason() + " " + r.message());
            return r;
        }
        return post(SHOPS_PATH, body);
    }

    /** 探活 / 密钥配对自检（只发 timestamp） */
    public ApiModels.IngestResponse health() {
        if (apiSecret.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config",
                    "未配置 apiSecret：请在配置文件填写与站点 INGEST_SHARED_SECRET 一致的值");
            log(r.reason() + " " + r.message());
            return r;
        }
        if (baseUrl.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config", "未配置 apiBaseUrl");
            log(r.reason() + " " + r.message());
            return r;
        }
        return post(HEALTH_PATH, ApiModels.buildHealthRequest());
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    private ApiModels.IngestResponse post(String path, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String signature = hmacSha256Hex(payload);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header(SIGNATURE_HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            String responseBody = response.body();

            if (code == 401 || code == 403) {
                ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("auth",
                        "鉴权失败（HTTP " + code + "）：签名或密钥不匹配，请检查 apiSecret 是否与站点 INGEST_SHARED_SECRET 一致");
                log(r.reason() + " " + r.message());
                return r;
            }
            if (code == 429) {
                ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("rate",
                        "上报过于频繁（HTTP 429）：站点限流桶已打满，稍后会自动重试");
                log(r.reason() + " " + r.message());
                return r;
            }
            if (code >= 500) {
                ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("server", "站点服务端错误（HTTP " + code + "），稍后会自动重试");
                log(r.reason() + " " + r.message());
                return r;
            }
            if (code < 200 || code >= 300) {
                ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("server",
                        "站点返回异常状态码 HTTP " + code + "（预期 200）");
                log(r.reason() + " " + r.message());
                return r;
            }

            ApiModels.IngestResponse parsed = ApiModels.parseIngestResponse(responseBody);
            if (!parsed.ok() && parsed.reason() == null) {
                ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("server",
                        "站点未接受这批数据：" + (parsed.message() == null ? "响应 ok=false" : parsed.message()));
                log(r.reason() + " " + r.message());
                return r;
            }
            return parsed;
        } catch (HttpConnectTimeoutException e) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("timeout", "连接站点超时（" + CONNECT_TIMEOUT.toSeconds() + "s）：" + e.getMessage());
            log(r.reason() + " " + r.message());
            return r;
        } catch (HttpTimeoutException e) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("timeout", "上报请求超时（" + REQUEST_TIMEOUT.toSeconds() + "s）：" + e.getMessage());
            log(r.reason() + " " + r.message());
            return r;
        } catch (IOException e) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("network", "无法连接站点 " + baseUrl + "：" + e.getMessage());
            log(r.reason() + " " + r.message());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("interrupted", "上报被中断");
            log(r.reason() + " " + r.message());
            return r;
        }
    }

    /**
     * 只发心跳键的 POST（shops 为空数组）。
     *
     * <p>正常流程下心跳随普通上传批发出，本方法只在「队列空但有攒着的心跳」时
     * 兜底（例如一个静止的服务器上没有任何新牌子）。站点允许空 shops：
     * 它只对 heartbeatKeys 做 touchLastSeen，不写入新行。
     */
    public ApiModels.IngestResponse postHeartbeatOnly(List<String> heartbeatKeys) {
        if (apiSecret.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config",
                    "未配置 apiSecret：请在配置文件填写与站点 INGEST_SHARED_SECRET 一致的值");
            log(r.reason() + " " + r.message());
            return r;
        }
        if (baseUrl.isEmpty()) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config", "未配置 apiBaseUrl");
            log(r.reason() + " " + r.message());
            return r;
        }
        String body;
        try {
            body = ApiModels.buildHeartbeatOnlyRequest(heartbeatKeys);
        } catch (RuntimeException e) {
            ApiModels.IngestResponse r = ApiModels.IngestResponse.failure("config", "请求体构造失败：" + e.getMessage());
            log(r.reason() + " " + r.message());
            return r;
        }
        return post(SHOPS_PATH, body);
    }

    private String hmacSha256Hex(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private void log(String msg) {
        if (log != null) {
            try {
                log.accept(msg);
            } catch (RuntimeException ignored) {
                // 日志出口异常不能影响上报主流程
            }
        }
    }
}
