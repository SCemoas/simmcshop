package com.shopsignscanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 站点 ingest 接口的请求体 / 响应体编解码（纯逻辑，不依赖任何 Fabric / Minecraft 类）。
 *
 * <p>契约见仓库外部的 {@code ingest-contract.md}，这里是它在 mod 侧的唯一实现。
 * 请求体 {@code /api/ingest/shops} 形如：
 * <pre>
 * {
 *   "timestamp": 1729420800000,
 *   "client": { "version": "2.2.3", "server": "play.example.com" },
 *   "shops": [ { "seenAt": "...", "lastSeenAt": "...", "owner": "...", "type": "...",
 *                "quantity": 1, "item": "...", "price": 12.5, "priceRaw": "...",
 *                "world": "...", "x": 0, "y": 0, "z": 0, "side": "...",
 *                "server": "...", "raw": "...", "land": "...", "gateway": "..." } ],
 *   "heartbeatKeys": [ "&lt;location_key&gt;", ... ]
 * }
 * </pre>
 *
 * <p><b>为什么手写而不是引 GSON</b>：本仓库的依赖面刻意保持「只有 JDK + fabric」，
 * 客户端 classpath 里虽然能蹭到 fabric-loader 传递进来的 GSON，但那是 loader 的
 * 内部实现细节，随 loader 升级随时可能变。{@code Outbox} 里已经有一份久经考验的
 * 手写转义 / 解析实现，这里沿用它（转义集与之一致，保证磁盘队列 → HTTP 的字段
 * 内容完全不变）。
 *
 * <p><b>字段名与 {@code ShopRecord} 不同</b>：契约要求 shops 项用
 * {@code seenAt}/{@code lastSeenAt}，而 {@code ShopRecord.time} 同时充当这两个值
 * （直连入库时 {@code bind()} 也是把同一个 time 写进 first/last/scan 三个时间列）。
 * 因此本类收的是字段值而不是 {@code ShopRecord}，映射在调用方
 * （{@code ApiReporter}）完成，纯逻辑层不反向依赖 client 包。
 */
public final class ApiModels {

    /** 单次请求 shops 上限（契约第 3 节：≤200） */
    public static final int MAX_SHOPS_PER_REQUEST = 200;

    /** 单次请求 heartbeatKeys 上限（契约第 3 节：≤5000，对齐 DatabaseReporter.HEARTBEAT_CHUNK=500） */
    public static final int MAX_HEARTBEAT_KEYS_PER_REQUEST = 5000;

    private ApiModels() {
    }

    // -----------------------------------------------------------------
    // 请求侧
    // -----------------------------------------------------------------

    /**
     * 一条待 upsert 的商店（已按契约字段名平铺，顺序即 JSON 顺序）。
     *
     * @param seenAt     首次发现时间，"YYYY-MM-DD HH:MM:SS"
     * @param lastSeenAt 最近一次看到的时间，同上格式
     */
    public record ShopPayload(
            String seenAt,
            String lastSeenAt,
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
            String gateway
    ) {
    }

    /**
     * 构造一次 /api/ingest/shops 请求体。
     *
     * <p>{@code timestamp} 用调用瞬间的 {@link System#currentTimeMillis()}，
     * 站点按 ±5 分钟窗口校验，因此这里不做任何缓存。
     *
     * @param version       mod 版本（fabric.mod.json 的 version）
     * @param serverName    当前服务器标识（单机为 "singleplayer"）
     * @param shops         本批商店，数量不得超过 {@value #MAX_SHOPS_PER_REQUEST}
     * @param heartbeatKeys 本批随行的心跳 key，可为空列表
     * @return 待签名的原始 JSON 字符串（UTF-8 编码后才做 HMAC）
     */
    public static String buildShopsRequest(String version, String serverName, List<ShopPayload> shops, List<String> heartbeatKeys) {
        if (shops == null || shops.isEmpty()) {
            throw new IllegalArgumentException("shops 不能为空（心跳也应至少带 1 个 key）");
        }
        if (shops.size() > MAX_SHOPS_PER_REQUEST) {
            throw new IllegalArgumentException("shops 超过单次上限 " + MAX_SHOPS_PER_REQUEST + ": " + shops.size());
        }
        List<String> keys = heartbeatKeys == null ? List.of() : heartbeatKeys;
        if (keys.size() > MAX_HEARTBEAT_KEYS_PER_REQUEST) {
            throw new IllegalArgumentException("heartbeatKeys 超过单次上限 " + MAX_HEARTBEAT_KEYS_PER_REQUEST + ": " + keys.size());
        }

        StringBuilder sb = new StringBuilder(512 + shops.size() * 256);
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"client\":{\"version\":").append(jsonString(version == null ? "" : version));
        sb.append(",\"server\":").append(jsonString(serverName == null ? "" : serverName)).append('}');

        sb.append(",\"shops\":[");
        for (int i = 0; i < shops.size(); i++) {
            if (i > 0) sb.append(',');
            appendShop(sb, shops.get(i));
        }
        sb.append(']');

        sb.append(",\"heartbeatKeys\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(keys.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 构造一次 /api/ingest/health 请求体（探活 / 密钥配对自检） */
    public static String buildHealthRequest() {
        return "{\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    /**
     * 只带心跳键、不带 shops 的请求体（兜底路径）。
     *
     * <p>正常流程下心跳随普通上传批发出（{@link #buildShopsRequest}），本方法
     * 只在「队列空但攒了一批心跳」时兜底：契约允许 shops 为空数组
     * （站点只做 touchLastSeen），而 {@link #buildShopsRequest} 明确禁止空 shops。
     */
    public static String buildHeartbeatOnlyRequest(List<String> heartbeatKeys) {
        List<String> keys = heartbeatKeys == null ? List.of() : heartbeatKeys;
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("heartbeatKeys 不能为空");
        }
        if (keys.size() > MAX_HEARTBEAT_KEYS_PER_REQUEST) {
            throw new IllegalArgumentException("heartbeatKeys 超过单次上限 " + MAX_HEARTBEAT_KEYS_PER_REQUEST + ": " + keys.size());
        }
        StringBuilder sb = new StringBuilder(128 + keys.size() * 34);
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"client\":{\"version\":\"\",\"server\":\"\"}");
        sb.append(",\"shops\":[],\"heartbeatKeys\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(keys.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendShop(StringBuilder sb, ShopPayload p) {
        sb.append("{\"seenAt\":").append(jsonString(p.seenAt()));
        sb.append(",\"lastSeenAt\":").append(jsonString(p.lastSeenAt()));
        sb.append(",\"owner\":").append(jsonString(p.owner()));
        sb.append(",\"type\":").append(jsonString(p.type()));
        // quantity 允许 -1（QUANTITY_INFINITE，「无限」商店）；站点 validator 的
        // 下限就是 -1，负得更离谱的值在客户端侧已由 parse() 钳到 0，不重复处理。
        sb.append(",\"quantity\":").append(p.quantity());
        sb.append(",\"item\":").append(jsonString(p.item()));
        sb.append(",\"price\":").append(jsonNumber(p.price()));
        sb.append(",\"priceRaw\":").append(jsonString(p.priceRaw()));
        sb.append(",\"world\":").append(jsonString(p.world()));
        sb.append(",\"x\":").append(p.x());
        sb.append(",\"y\":").append(p.y());
        sb.append(",\"z\":").append(p.z());
        sb.append(",\"side\":").append(jsonString(p.side()));
        sb.append(",\"server\":").append(jsonString(p.server()));
        sb.append(",\"raw\":").append(jsonString(p.raw()));
        sb.append(",\"land\":").append(jsonString(p.land()));
        sb.append(",\"gateway\":").append(jsonString(p.gateway()));
        sb.append('}');
    }

    // -----------------------------------------------------------------
    // 响应侧
    // -----------------------------------------------------------------

    /**
     * ingest 接口响应。
     *
     * @param ok           是否整体成功
     * @param saved        本次写入/更新的商店行数
     * @param heartbeated 本次 touchLastSeen 的心跳行数
     * @param rejected    被拒条数（不计服务端错误，通常是数据超长/越界）
     * @param time         服务端时间（原样透传，仅排障用）
     * @param reason       失败原因码（ok=false 时非空），见 {@link ApiClient} 的分诊
     * @param message      人类可读的失败说明
     */
    public record IngestResponse(boolean ok, int saved, int heartbeated, int rejected, String time, String reason, String message) {

        public static IngestResponse failure(String reason, String message) {
            return new IngestResponse(false, 0, 0, 0, null, reason, message);
        }
    }

    /**
     * 解析响应体：{@code {ok, saved, heartbeated, rejected, time}}。
     *
     * <p>解析失败（空 body、非 JSON）时返回 ok=false / reason=parse，
     * 由上层当作「服务端错误」处理并重试——宁可多试一次，也不要把成功当失败。
     */
    public static IngestResponse parseIngestResponse(String body) {
        if (body == null || body.isBlank()) {
            return IngestResponse.failure("parse", "响应体为空");
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return IngestResponse.failure("parse", "响应不是 JSON 对象");
        }
        try {
            Map<String, String> fields = parseFlatObject(trimmed);
            boolean ok = Boolean.parseBoolean(fields.getOrDefault("ok", "false"));
            String time = fields.get("time");
            return new IngestResponse(
                    ok,
                    parseInt(fields.get("saved")),
                    parseInt(fields.get("heartbeated")),
                    parseInt(fields.get("rejected")),
                    time == null || time.isEmpty() || "null".equals(time) ? null : time,
                    fields.get("reason"),
                    fields.get("message")
            );
        } catch (RuntimeException e) {
            return IngestResponse.failure("parse", "响应解析失败：" + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // 极简 JSON 编解码（自包含，勿引第三方库）
    // -----------------------------------------------------------------

    /**
     * JSON 字符串转义：双引号、反斜杠、{@code \n \r \t} 以及其余 C0 控制字符。
     * 中文等非 ASCII 字符按 UTF-8 原样输出（契约与站点都以 UTF-8 解码）。
     */
    public static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
        return sb.append('"').toString();
    }

    /**
     * JSON 数字：契约要求 price 为数字，且 {@code |v| >= 1e15} 必须由 mod 发送前拦下。
     * 这里只负责把 double 写成 JSON 字面量（NaN/Infinity 在 JSON 里非法，直接给 null
     * 让服务端记 rejected，而不是发出让整批请求体非法的脏值）。
     */
    public static String jsonNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "null";
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            long rounded = (long) value;
            // 避免 12.0 被写成 12e0 之类站点不吃的形式
            return Long.toString(rounded);
        }
        String s = String.format(Locale.ROOT, "%.6f", value);
        // 去掉尾随零：12.500000 -> 12.5
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end++;
        return s.substring(0, end);
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析一个扁平 JSON 对象（值为字符串 / 数字 / 布尔 / null，无嵌套对象）。
     * 返回 key -> 原始值文本（字符串值不带引号）。
     */
    private static Map<String, String> parseFlatObject(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = skipWs(json, 1);
        while (i < json.length()) {
            if (json.charAt(i) == '}') return out;
            if (json.charAt(i) == ',') {
                i = skipWs(json, i + 1);
                continue;
            }
            if (json.charAt(i) != '"') throw new IllegalArgumentException("位置 " + i + " 缺少字段名");
            String key = readString(json, i);
            i = skipWs(json, endOfString(json, i));
            if (i >= json.length() || json.charAt(i) != ':') throw new IllegalArgumentException("字段 " + key + " 后缺少冒号");
            i = skipWs(json, i + 1);
            String value;
            if (i < json.length() && json.charAt(i) == '"') {
                value = readString(json, i);
                i = skipWs(json, endOfString(json, i));
            } else {
                int start = i;
                while (i < json.length() && ",}] \t\r\n".indexOf(json.charAt(i)) < 0) i++;
                value = json.substring(start, i);
            }
            out.put(key, value);
            i = skipWs(json, i);
        }
        // 走到这里说明整个字符串都没遇到右花括号：截断的 JSON。
        // 直接抛出去，让上层按 parse 失败处理——绝不把半截响应当成成功。
        throw new IllegalArgumentException("JSON 对象缺少右花括号");
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    private static int endOfString(String s, int quoteIndex) {
        int p = quoteIndex + 1;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\\') {
                p += 2;
                continue;
            }
            if (c == '"') return p + 1;
            p++;
        }
        return s.length();
    }

    /** 读取一个带引号的 JSON 字符串并反转义（与 {@link #jsonString} 互为逆运算） */
    private static String readString(String s, int quoteIndex) {
        if (s.charAt(quoteIndex) != '"') throw new IllegalArgumentException("不是 JSON 字符串");
        StringBuilder sb = new StringBuilder();
        int p = quoteIndex + 1;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\\') {
                p++;
                if (p >= s.length()) break;
                char next = s.charAt(p);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (p + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(p + 1, p + 5), 16));
                            p += 4;
                        }
                    }
                    default -> sb.append(next);
                }
                p++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                p++;
            }
        }
        return sb.toString();
    }

    /** 便利方法：把 List 转成可变 ArrayList（测试与调用方都要用） */
    public static <T> List<T> mutableList(List<T> src) {
        return new ArrayList<>(src);
    }
}
