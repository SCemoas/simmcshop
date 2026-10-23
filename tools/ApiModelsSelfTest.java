package com.shopsignscanner;

import java.util.Collections;
import java.util.List;

/**
 * {@link ApiModels} 的自检程序（2.2.3 新增）。
 *
 * <h2>为什么是这个形式（main 方法而不是 JUnit）</h2>
 * 本仓库 build.gradle 没有 test 依赖：{@code gradlew dependencies
 * --configuration testCompileClasspath} 里既没有 junit / jupiter，也没有
 * fabric 的 gametest 之外任何测试库，本地 Gradle 缓存里也搜不到 junit-jupiter
 * 的 jar。而 Gradle 一旦发现 {@code src/test} 下有源码就会创建 test 任务，
 * 且该源集里没有 @Test 时会直接让 {@code gradlew build} 失败
 * （"There are test sources present ... did not discover any tests"）。
 * 因此这里沿用仓库既有的 tools/ 单文件自检方式，与
 * {@code ColumnLimitsSelfTest} / {@code OutboxSelfTest} 完全一致：
 *
 * <pre>
 *   javac -encoding UTF-8 -d out \
 *         src/main/java/com/shopsignscanner/ApiModels.java \
 *         src/main/java/com/shopsignscanner/ApiClient.java \
 *         tools/ApiModelsSelfTest.java
 *   java -Dfile.encoding=UTF-8 -cp out com.shopsignscanner.ApiModelsSelfTest
 * </pre>
 *
 * <h2>这些自检守住的静默故障</h2>
 * <ul>
 *   <li>JSON 转义漏字符 → 牌面里的引号/换行让整个请求体非法，站点整批拒收；</li>
 *   <li>响应解析把 ok=false 当成功 → 数据其实没写进去，面板却显示已上传；</li>
 *   <li>请求体漏 heartbeatKeys → 商店被拆后网站永远显示它；</li>
 *   <li>超上限不拦 → 站点直接 400，整批重试也救不回来。</li>
 * </ul>
 *
 * <p>只测纯 JSON 编解码：不碰 Fabric/Minecraft 类，也不做真 HTTP——
 * ApiClient 的签名与错误分诊需要活站点，放进 CI 只会制造 flaky 失败，
 * 改为在 README 的手动自检节说明。
 */
public final class ApiModelsSelfTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		jsonString();
		jsonNumber();
		requestBodies();
		healthRequest();
		heartbeatOnly();
		responseParsing();
		misc();

		System.out.println();
		System.out.println(failed == 0
				? "全部通过：" + passed + " 项"
				: "失败 " + failed + " 项 / 共 " + (passed + failed) + " 项");
		if (failed != 0) System.exit(1);
	}

	// -----------------------------------------------------------------
	// 1. jsonString：契约要求的转义集
	// -----------------------------------------------------------------

	private static void jsonString() {
		check("双引号被转义", "\"a\\\"b\"".equals(ApiModels.jsonString("a\"b")));
		check("反斜杠被转义", "\"a\\\\b\"".equals(ApiModels.jsonString("a\\b")));
		check("换行被转义", "\"a\\nb\"".equals(ApiModels.jsonString("a\nb")));
		check("回车被转义", "\"a\\rb\"".equals(ApiModels.jsonString("a\rb")));
		check("制表符被转义", "\"a\\tb\"".equals(ApiModels.jsonString("a\tb")));

		// 0x01 没有简写形式，必须转成反斜杠 u0001，否则请求体是非法 JSON。
		// 注意：这里不能写裸控制字符，也不能在注释里写反斜杠 u 加四位十六进制
		// （javac 的 Unicode 预处理会先扫描注释，见 JLS 3.3），所以用 char 构造。
		check("其他控制字符走反斜杠 u 四位十六进制",
				"\"a\\u0001b\"".equals(ApiModels.jsonString("a" + (char) 1 + "b")));

		check("普通字符原样", "\"plain\"".equals(ApiModels.jsonString("plain")));
		check("null 输出 JSON null 而不是字符串 null", "null".equals(ApiModels.jsonString(null)));

		String s = "店主·收购·下界合金锭";
		check("中文按 UTF-8 原样输出", ("\"" + s + "\"").equals(ApiModels.jsonString(s)));
		check("中文不被拆成反斜杠 u 转义", !ApiModels.jsonString(s).contains("\\u"));

		// 转成反斜杠 u 虽然合法，但会让站点日志与调试变难；契约双方都按 UTF-8 解码。
		// 这里再钉一个最常见的牌面场景：中文 + 换行 + 引号同时在 owner 里。
		String messy = "店主\"收购\"下界\n合金锭";
		String escaped = ApiModels.jsonString(messy);
		check("中文与引号换行混排时仍可解析",
				messy.equals(ApiModels.parseIngestResponse("{\"message\":" + escaped + "}").message()));
	}

	// -----------------------------------------------------------------
	// 2. jsonNumber：price 是 JSON 数字
	// -----------------------------------------------------------------

	private static void jsonNumber() {
		check("整数不带小数点", "12".equals(ApiModels.jsonNumber(12)));
		check("小数去尾随零", "12.5".equals(ApiModels.jsonNumber(12.500)));
		check("负号保留", "-64".equals(ApiModels.jsonNumber(-64)));
		check("零", "0".equals(ApiModels.jsonNumber(0)));
		check("NaN 输出 null", "null".equals(ApiModels.jsonNumber(Double.NaN)));
		check("Infinity 输出 null", "null".equals(ApiModels.jsonNumber(Double.POSITIVE_INFINITY)));
		check("大整数不用科学计数法", "100000000000000".equals(ApiModels.jsonNumber(1e14)));
		check("小数保留有效位", "0.3".equals(ApiModels.jsonNumber(0.1 + 0.2)));
		// 契约：|v| >= 1e15 由 mod 发送前拦下（ApiReporter 抛 data），
		// 这里只确认 jsonNumber 本身不会发出科学计数法让站点炸掉。
		check("1e15 附近不用科学计数法", !ApiModels.jsonNumber(1e15).contains("E"));
	}

	// -----------------------------------------------------------------
	// 3. 请求体：契约第 3 节的字段名与顺序
	// -----------------------------------------------------------------

	private static void requestBodies() {
		String body = ApiModels.buildShopsRequest("2.2.3", "s", List.of(sample()), List.of());
		check("以 { 开头且以 } 结尾", body.startsWith("{") && body.endsWith("}"));
		check("含 timestamp", body.contains("\"timestamp\":"));
		check("含 client.version", body.contains("\"client\":{\"version\":\"2.2.3\""));
		check("含 client.server",
				ApiModels.buildShopsRequest("2.2.3", "play.example.com", List.of(sample()), List.of())
						.contains("\"server\":\"play.example.com\"}"));
		check("shops 是数组", body.contains("\"shops\":[{"));
		check("字段名 seenAt / lastSeenAt",
				body.contains("\"seenAt\":\"2026-10-20 12:00:00\"")
						&& body.contains("\"lastSeenAt\":\"2026-10-20 12:00:00\""));
		check("owner 里的引号已转义", body.contains("\"owner\":\"店主\\\"带引号\\\"\""));
		check("quantity / price 字段名", body.contains("\"quantity\":12") && body.contains("\"price\":1000.5"));
		check("raw 里的换行已转义", body.contains("\"raw\":\"第一行\\n第二行\""));
		// sample() 的 land/gateway 都非空，空串场景用 minimal() 单独验
		check("gateway 原样（含空格与括号）", body.contains("\"gateway\":\"中央车站 (120m)\""));
		String minBody = ApiModels.buildShopsRequest("v", "s", List.of(minimal()), List.of());
		check("空 land / gateway 是空字符串而不是缺失",
				minBody.contains("\"land\":\"\",\"gateway\":\"\""));

		String withKeys = ApiModels.buildShopsRequest("2.2.3", "s", List.of(sample()),
				List.of("key-1", "key-2"));
		check("heartbeatKeys 随批发出，且在 shops 之后",
				withKeys.endsWith(",\"heartbeatKeys\":[\"key-1\",\"key-2\"]}")
						&& withKeys.indexOf("\"shops\"") < withKeys.indexOf("\"heartbeatKeys\""));

		String neg = ApiModels.buildShopsRequest("v", "s", List.of(
				new ApiModels.ShopPayload("t", "t", "o", "sell", -1, "i", 1, "p", "w", 0, 0, 0,
						"front", "srv", "r", "", "")), List.of());
		check("quantity=-1（官方无限店）原样透传，与站点 validator 下限一致",
				neg.contains("\"quantity\":-1"));

		throwsAny("空 shops 抛异常", () -> ApiModels.buildShopsRequest("v", "s", List.of(), List.of()));
		throwsAny("null shops 抛异常", () -> ApiModels.buildShopsRequest("v", "s", null, List.of()));
		throwsAny("超过 200 条抛异常", () -> ApiModels.buildShopsRequest("v", "s",
				Collections.nCopies(ApiModels.MAX_SHOPS_PER_REQUEST + 1, minimal()), List.of()));
		throwsAny("heartbeatKeys 超过 5000 抛异常", () -> ApiModels.buildShopsRequest("v", "s",
				List.of(minimal()),
				Collections.nCopies(ApiModels.MAX_HEARTBEAT_KEYS_PER_REQUEST + 1, "k")));

		check("恰好 200 条可以通过", !hasThrown(() -> ApiModels.buildShopsRequest("v", "s",
				Collections.nCopies(ApiModels.MAX_SHOPS_PER_REQUEST, minimal()), List.of())));
		check("恰好 5000 个心跳键可以通过", !hasThrown(() -> ApiModels.buildShopsRequest("v", "s",
				List.of(minimal()),
				Collections.nCopies(ApiModels.MAX_HEARTBEAT_KEYS_PER_REQUEST, "k"))));

		// 时间戳必须是「调用瞬间」，不能缓存：站点按 ±5 分钟窗口校验。
		// 证明方式：把 stamp 取出来与本次调用前后的 currentTimeMillis() 比对。
		// 只要 stamp 落在 [before, after] 区间（两端允许时钟跳变造成的少量偏差），
		// 就说明它是逐次取 currentTimeMillis() 算出来的，而不是某个常量字段。
		// 若实现改成静态常量，这条必然挂。
		ApiModels.ShopPayload one = minimal();
		long before = System.currentTimeMillis();
		long stamp = stampOf(ApiModels.buildShopsRequest("v", "s", List.of(one), List.of()));
		long after = System.currentTimeMillis();
		check("timestamp 是调用瞬间", stamp >= before - 50 && stamp <= after + 50);
	}

	// -----------------------------------------------------------------
	// 4. 探活体
	// -----------------------------------------------------------------

	private static void healthRequest() {
		String body = ApiModels.buildHealthRequest();
		check("以 { 开头且以 } 结尾", body.startsWith("{\"timestamp\":") && body.endsWith("}"));
		check("不含 shops", !body.contains("shops"));
	}

	// -----------------------------------------------------------------
	// 5. 兜底路径：只发心跳键
	// -----------------------------------------------------------------

	private static void heartbeatOnly() {
		String body = ApiModels.buildHeartbeatOnlyRequest(List.of("k1", "k2"));
		check("shops 是空数组而不是缺失", body.contains("\"shops\":[]"));
		check("heartbeatKeys 完整", body.endsWith("\"heartbeatKeys\":[\"k1\",\"k2\"]}"));
		check("仍带 timestamp（站点照样校验时间窗）", body.contains("\"timestamp\":"));
		throwsAny("空 keys 抛异常", () -> ApiModels.buildHeartbeatOnlyRequest(List.of()));
	}

	// -----------------------------------------------------------------
	// 6. 响应解析
	// -----------------------------------------------------------------

	private static void responseParsing() {
		ApiModels.IngestResponse r = ApiModels.parseIngestResponse(
				"{\"ok\":true,\"saved\":7,\"heartbeated\":512,\"rejected\":2,\"time\":\"2026-10-20 12:00:00\"}");
		check("ok=true：saved / heartbeated / rejected / time 全部取出",
				r.ok() && r.saved() == 7 && r.heartbeated() == 512 && r.rejected() == 2
						&& "2026-10-20 12:00:00".equals(r.time()));
		check("成功时 reason 为空", r.reason() == null);

		ApiModels.IngestResponse f = ApiModels.parseIngestResponse(
				"{\"ok\":false,\"reason\":\"timestamp\",\"message\":\"时间偏差超出 5 分钟\"}");
		check("ok=false：reason 与 message 被取出",
				!f.ok() && "timestamp".equals(f.reason()) && "时间偏差超出 5 分钟".equals(f.message()));

		// 站点 500 时常返回 ok:false 且不带 reason，ApiClient 据此判 server 错误。
		ApiModels.IngestResponse serverish = ApiModels.parseIngestResponse(
				"{\"ok\":false,\"message\":\"deadlock\"}");
		check("ok=false 且无 reason 时 reason 仍为 null（供 ApiClient 判 server）",
				!serverish.ok() && serverish.reason() == null && "deadlock".equals(serverish.message()));

		check("空 body 落到 parse，不抛异常", !ApiModels.parseIngestResponse("").ok());
		check("null body 落到 parse", !ApiModels.parseIngestResponse(null).ok());
		check("HTML 页面落到 parse", !ApiModels.parseIngestResponse("<html>502</html>").ok());
		check("截断 JSON 落到 parse，不把半截响应当成成功",
				!ApiModels.parseIngestResponse("{\"ok\":true").ok());
		check("无法解析时 reason 都是 parse",
				"parse".equals(ApiModels.parseIngestResponse("").reason())
						&& "parse".equals(ApiModels.parseIngestResponse("<html>").reason()));

		// 转义与解析互为逆运算
		String raw = "带\"引号\"\\反斜杠\n换行\r回车\t制表 中文";
		String body = "{\"ok\":true,\"saved\":1,\"heartbeated\":0,\"rejected\":0,\"time\":null,\"message\":"
				+ ApiModels.jsonString(raw) + "}";
		ApiModels.IngestResponse rt = ApiModels.parseIngestResponse(body);
		check("转义与解析互为逆运算：message 往返一致", raw.equals(rt.message()));
		check("time 为 null 时取到 null", rt.time() == null);
	}

	// -----------------------------------------------------------------
	// 7. 其他
	// -----------------------------------------------------------------

	private static void misc() {
		List<String> copy = ApiModels.mutableList(List.of("a", "b"));
		copy.add("c");
		check("mutableList 返回可变副本", copy.size() == 3 && List.of("a", "b").size() == 2);
	}

	// -----------------------------------------------------------------
	// 工具
	// -----------------------------------------------------------------

	/** 构造一条「什么坑都踩一遍」的记录：中文、引号、换行、负坐标、括号网关 */
	private static ApiModels.ShopPayload sample() {
		return new ApiModels.ShopPayload(
				"2026-10-20 12:00:00", "2026-10-20 12:00:00", "店主\"带引号\"", "sell", 12,
				"钻石", 1000.5, "$1,000.5", "minecraft:overworld", 100, 64, -200, "front",
				"play.example.com", "第一行\n第二行", "示例城", "中央车站 (120m)");
	}

	private static ApiModels.ShopPayload minimal() {
		return new ApiModels.ShopPayload("t", "t", "o", "sell", 1, "i", 1, "p", "w", 0, 0, 0,
				"front", "srv", "r", "", "");
	}

	/** 从请求体里把 timestamp 数字抠出来（自检「是调用瞬间」用） */
	private static long stampOf(String body) {
		int i = body.indexOf("\"timestamp\":") + "\"timestamp\":".length();
		int j = i;
		while (j < body.length() && Character.isDigit(body.charAt(j))) j++;
		return Long.parseLong(body.substring(i, j));
	}

	private static boolean hasThrown(Runnable r) {
		try {
			r.run();
			return false;
		} catch (RuntimeException e) {
			return true;
		}
	}

	private static void throwsAny(String name, Runnable r) {
		check(name, hasThrown(r));
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
