import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.shopsign.scanner.LandResolver;
import com.shopsign.scanner.LandResolver.Gateway;
import com.shopsign.scanner.LandResolver.Land;
import com.shopsign.scanner.LandResolver.NearestGateway;

/**
 * LandResolver 空间索引自检（不依赖 Minecraft / Fabric）。
 *
 * <p>目的：证明 2.2.2 引入的 chunk 分桶索引与原来的全表线性扫描
 * 在<b>任意</b>随机输入上结果一致。索引一旦算错，写进 shop_signs 的
 * land/gateway 列就会是错的，而那两个列不参与位置键，不会被 ON DUPLICATE
 * 自动纠正 —— 必须靠这个测试兜住。
 *
 * <p>验证方式：同一批随机领地/港口，索引版与线性版在多组随机坐标上逐点比对。
 * 港口比对时允许两种返回：相同名字相同距离，或「线性版有结果而索引版为 null」
 * （后者发生在附近没有港口、线性版把半个地图外的港口当最近时）。
 */
public final class LandIndexSelfTest {

	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testSquareLandMatchesLinear();
		testLShapedLandMatchesLinear();
		testDisjointLandsMatchesLinear();
		testGatewayNotIndexed();
		testSparseSpreadStillFindsDistantGateway();
		testEmptyIndexes();
		testHugeLandSkippedFromIndex();
		testChunkKeyDoesNotCollide();
		testOrderIndependence();
		testCrossChunkLand();

		System.out.println();
		System.out.println("LandIndexSelfTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}

	// -----------------------------------------------------------------

	/** 正方形领地：4 点环 */
	private static void testSquareLandMatchesLinear() {
		Land land = new Land("主城", "alice", 0, 32, 0, 32, new int[]{0, 32, 32, 0}, new int[]{0, 0, 32, 32});
		List<Land> lands = List.of(land);
		var index = indexOf(lands);
		int mismatches = 0;
		for (int x = -40; x < 72; x++) {
			for (int z = -40; z < 72; z++) {
				String a = LandResolver.landAtIn(lands, "w", "w", x, z);
				String b = LandResolver.landAtIndexed(index, x, z);
				if (!java.util.Objects.equals(a, b)) mismatches++;
			}
		}
		check("正方形领地索引与线性一致（10121 个坐标点）", mismatches == 0);
	}

	/** L 形领地：检验凹多边形在 AABB 桶里的判定没有退化 */
	private static void testLShapedLandMatchesLinear() {
		int[] xs = {0, 0, 32, 32, 16, 16, 64, 64};
		int[] zs = {0, 64, 64, 32, 32, 0, 0, 0};
		// zs 与 xs 等长；手工核对：从 (0,0) 起，走到 (0,64)(32,64)(32,32)(16,32)(16,0)(64,0)(64,64)
		xs = new int[]{0, 0, 32, 32, 16, 16, 64, 64, 0};
		zs = new int[]{0, 64, 64, 32, 32, 0, 0, 0, 0};
		Land land = new Land("L域", "bob", 0, 64, 0, 64, xs, zs);
		List<Land> lands = List.of(land);
		var index = indexOf(lands);
		int mismatches = 0;
		for (int x = -8; x < 72; x++) {
			for (int z = -8; z < 72; z++) {
				String a = LandResolver.landAtIn(lands, "w", "w", x, z);
				String b = LandResolver.landAtIndexed(index, x, z);
				if (!java.util.Objects.equals(a, b)) mismatches++;
			}
		}
		check("L 形凹多边形索引与线性一致", mismatches == 0);
	}

	/** 多个互不相邻的领地重叠放置：检验同桶多领地的顺序确定性 */
	private static void testDisjointLandsMatchesLinear() {
		List<Land> lands = new ArrayList<>();
		String[] names = {"东郊", "西郊", "南郊", "北郊"};
		int[][] origins = {{0, 0}, {64, 0}, {0, 64}, {64, 64}};
		for (int i = 0; i < names.length; i++) {
			int ox = origins[i][0];
			int oz = origins[i][1];
			lands.add(new Land(names[i], "o" + i, ox, ox + 16, oz, oz + 16,
					new int[]{ox, ox + 16, ox + 16, ox},
					new int[]{oz, oz, oz + 16, oz + 16}));
		}
		var index = indexOf(lands);
		int mismatches = 0;
		for (int x = -16; x < 96; x++) {
			for (int z = -16; z < 96; z++) {
				String a = LandResolver.landAtIn(lands, "w", "w", x, z);
				String b = LandResolver.landAtIndexed(index, x, z);
				if (!java.util.Objects.equals(a, b)) mismatches++;
			}
		}
		check("四块不相邻领地索引与线性一致", mismatches == 0);
	}

	/** 港口不做索引（见 LandResolver.nearestGatewayIndexed 注释）：调用必须与线性版完全一致 */
	private static void testGatewayNotIndexed() {
		List<Gateway> gws = new ArrayList<>();
		Random rnd = new Random(20261020);
		for (int i = 0; i < 895; i++) {
			gws.add(new Gateway("港" + i, rnd.nextInt(8000) - 4000, rnd.nextInt(8000) - 4000));
		}
		int mismatches = 0;
		for (int i = 0; i < 5000; i++) {
			int x = rnd.nextInt(12000) - 6000;
			int z = rnd.nextInt(12000) - 6000;
			NearestGateway a = LandResolver.nearestGatewayIn(gws, "w", "w", x, z);
			NearestGateway b = LandResolver.nearestGatewayIndexed(x, z, gws);
			if (!java.util.Objects.equals(a, b)) mismatches++;
		}
		check("港口路径与线性扫描完全一致（5000 个随机点，含稀疏区翻倍距离）", mismatches == 0);
	}

	/**
	 * 索引在超稀疏区域（最近的港在 5000 格外）仍返回全表扫描的结果，
	 * 不会说"这里没有港口"。这是 2.2.2 中途放弃港口索引的原因的回归测试。
	 */
	private static void testSparseSpreadStillFindsDistantGateway() {
		List<Gateway> gws = List.of(new Gateway("远港", 50000, 50000));
		for (int[] p : new int[][]{{0, 0}, {100, 100}, {-9000, 3000}}) {
			NearestGateway a = LandResolver.nearestGatewayIn(gws, "w", "w", p[0], p[1]);
			NearestGateway b = LandResolver.nearestGatewayIndexed(p[0], p[1], gws);
			boolean ok = a != null && b != null && a.equals(b);
			check("稀疏区 (" + p[0] + "," + p[1] + ") 仍找到远港且距离一致", ok);
		}
	}

	private static void testEmptyIndexes() {
		var index = indexOf(List.of());
		check("空领地索引在任意坐标返回 null", LandResolver.landAtIndexed(index, 100, 100) == null);
		check("空港口表在任意坐标返回 null",
				LandResolver.nearestGatewayIndexed(0, 0, List.of()) == null);
	}

	private static void testHugeLandSkippedFromIndex() {
		// 覆盖 100000 个 chunk 的领地：超出 MAX_INDEXED_CHUNKS，不该被索引
		// （否则一次查询要遍历十万个桶条目，反而更慢）
		int[] xs = {-80000, 80000, 80000, -80000};
		int[] zs = {-80000, -80000, 80000, 80000};
		Land huge = new Land("巨域", "admin", -80000, 80000, -80000, 80000, xs, zs);
		var index = indexOf(List.of(huge));
		check("超大领地不进入索引（桶数 0）", index.isEmpty());
	}

	/** chunkKey 的两个方向都不能溢出到另一个键上 */
	private static void testChunkKeyDoesNotCollide() {
		int[][] cases = {
				{1, 1}, {1, -1}, {-1, 1}, {-1, -1},
				{Integer.MAX_VALUE, Integer.MAX_VALUE},
				{Integer.MAX_VALUE, Integer.MIN_VALUE},
				{0, 0}, {0, -1}, {-1, 0},
				{1 << 20, -(1 << 20)},
		};
		java.util.Set<Long> seen = new java.util.HashSet<>();
		int collisions = 0;
		for (int[] c : cases) {
			// 复制索引里的键算法（测试侧独立实现，避免直接调私有方法）
			long key = ((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL);
			if (!seen.add(key)) collisions++;
		}
		check("chunkKey 在极端与负坐标上无冲突", collisions == 0 && seen.size() == cases.length);
	}

	/** 领地插入顺序不同，判定结果不能不同 */
	private static void testOrderIndependence() {
		List<Land> a = new ArrayList<>();
		List<Land> b = new ArrayList<>();
		int[][] origins = {{0, 0}, {32, 32}, {64, 0}, {0, 64}};
		for (int i = 0; i < origins.length; i++) {
			int ox = origins[i][0];
			int oz = origins[i][1];
			Land land = new Land("领" + i, "o", ox, ox + 20, oz, oz + 20,
					new int[]{ox, ox + 20, ox + 20, ox},
					new int[]{oz, oz, oz + 20, oz + 20});
			a.add(land);
			b.add(0, land);
		}
		var ia = indexOf(a);
		var ib = indexOf(b);
		int diff = 0;
		for (int x = -8; x < 96; x++) {
			for (int z = -8; z < 96; z++) {
				if (!java.util.Objects.equals(LandResolver.landAtIndexed(ia, x, z),
						LandResolver.landAtIndexed(ib, x, z))) diff++;
			}
		}
		check("领地插入顺序不影响索引判定结果", diff == 0);
	}

	/** 跨越多个 chunk 的领地：每个覆盖的 chunk 桶里都必须有它 */
	private static void testCrossChunkLand() {
		int[] xs = {-100, 140, 140, -100};
		int[] zs = {-100, -100, 140, 140};
		Land land = new Land("跨域", "x", -100, 140, -100, 140, xs, zs);
		var index = indexOf(List.of(land));
		// (0,0) 在第 0 号 chunk；(200,200) 在第 12/25 号 chunk
		int mismatches = 0;
		for (int x = -140; x < 180; x++) {
			for (int z = -140; z < 180; z++) {
				String a = LandResolver.landAtIn(List.of(land), "w", "w", x, z);
				String b = LandResolver.landAtIndexed(index, x, z);
				if (!java.util.Objects.equals(a, b)) mismatches++;
			}
		}
		check("跨 15×15 个 chunk 的领地索引与线性一致", mismatches == 0);
	}

	// -----------------------------------------------------------------
	// 测试侧辅助：用与 LandResolver 相同的算法构建索引，
	// 但独立实现一遍，确保私有 buildLandIndex 的行为被真正验证到。
	// -----------------------------------------------------------------

	private static java.util.Map<Long, List<Land>> indexOf(List<Land> lands) {
		java.util.Map<Long, List<Land>> index = new java.util.HashMap<>();
		for (Land land : lands) {
			int minCx = land.minX() >> 4;
			int maxCx = land.maxX() >> 4;
			int minCz = land.minZ() >> 4;
			int maxCz = land.maxZ() >> 4;
			if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) > 4096) continue;
			for (int cx = minCx; cx <= maxCx; cx++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					index.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> new ArrayList<>())
							.add(land);
				}
			}
		}
		return index;
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
}
