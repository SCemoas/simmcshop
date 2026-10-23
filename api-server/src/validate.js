/**
 * 上报字段校验
 *
 * 校验规则与 mod 端 `ColumnLimits` / `ShopRecord` 的口径一致，
 * 也与站点时代的 ingestController 逐条对齐 —— 单个玩家的坏牌子
 * 不该堵死整批上报，因此**不合法只计 rejected，不抛 500**。
 *
 * 列宽上限的唯一来源是 mod 仓库的 `src/main/java/com/shopsignscanner/ColumnLimits.java`；
 * 改那边就必须改这里（`LIMITS` 块），否则超长值会让整批 INSERT 失败。
 */

/** 允许的告示牌类型（与 shop_signs.type 的 ENUM 一致） */
export const TYPES = ['sell', 'buy', 'sold_out', 'no_space'];

/** 允许的牌子面（与 shop_signs.side 的 ENUM 一致） */
export const SIDES = ['front', 'back'];

/** 价格绝对值上限：DECIMAL(20,4) 最多 16 位整数，超出必然截断报错 */
export const MAX_PRICE = 1e15;

/**
 * 列宽上限（字符数）
 *
 * 与 `ColumnLimits` 对应：
 *   owner 64 / item 255 / price_raw 255 / raw 1024 / land 64 / gateway 192
 *   world 128 / server 255
 */
export const LIMITS = {
  owner: 64,
  item: 255,
  priceRaw: 255,
  raw: 1024,
  land: 64,
  gateway: 192,
  world: 128,
  server: 255,
};

/** `YYYY-MM-DD HH:MM:SS`（本地时区，契约第 3 节） */
const TIME_RE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;

/**
 * 必填字段校验器
 * 返回 null 表示合法，返回字符串表示原因（会被写进 rejected 的日志）
 */
export const FIELD_VALIDATORS = {
  seenAt: (v) => (typeof v === 'string' && TIME_RE.test(v) ? null : 'seenAt 必须是 "YYYY-MM-DD HH:MM:SS"'),
  lastSeenAt: (v) =>
    typeof v === 'string' && TIME_RE.test(v) ? null : 'lastSeenAt 必须是 "YYYY-MM-DD HH:MM:SS"',
  owner: (v) =>
    typeof v === 'string' && v.length > 0 && v.length <= LIMITS.owner
      ? null
      : `owner 必须是非空字符串（≤${LIMITS.owner}）`,
  type: (v) => (TYPES.includes(v) ? null : `type 必须是 ${TYPES.join('/')} 之一`),
  side: (v) => (SIDES.includes(v) ? null : `side 必须是 ${SIDES.join('/')} 之一`),
  quantity: (v) => (Number.isInteger(v) && v >= -1 ? null : 'quantity 必须是整数（≥ -1，-1 表示无限）'),
  item: (v) =>
    typeof v === 'string' && v.length > 0 && v.length <= LIMITS.item
      ? null
      : `item 必须是非空字符串（≤${LIMITS.item}）`,
  price: (v) =>
    typeof v === 'number' && Number.isFinite(v) && Math.abs(v) < MAX_PRICE
      ? null
      : `price 必须是有限数且 |price| < ${MAX_PRICE}`,
  priceRaw: (v) =>
    typeof v === 'string' && v.length <= LIMITS.priceRaw
      ? null
      : `priceRaw 必须是字符串（≤${LIMITS.priceRaw}）`,
  world: (v) =>
    typeof v === 'string' && v.length > 0 && v.length <= LIMITS.world
      ? null
      : `world 必须是非空字符串（≤${LIMITS.world}）`,
  server: (v) =>
    typeof v === 'string' && v.length > 0 && v.length <= LIMITS.server
      ? null
      : `server 必须是非空字符串（≤${LIMITS.server}）`,
  x: (v) => (Number.isInteger(v) ? null : 'x 必须是整数'),
  y: (v) => (Number.isInteger(v) ? null : 'y 必须是整数'),
  z: (v) => (Number.isInteger(v) ? null : 'z 必须是整数'),
};

/** 可选字段：给了就必须合法；缺省由写入层补空串 */
export const OPTIONAL_VALIDATORS = {
  raw: (v) =>
    typeof v === 'string' && v.length <= LIMITS.raw ? null : `raw 必须是字符串（≤${LIMITS.raw}）`,
  land: (v) =>
    typeof v === 'string' && v.length <= LIMITS.land ? null : `land 必须是字符串（≤${LIMITS.land}）`,
  gateway: (v) =>
    typeof v === 'string' && v.length <= LIMITS.gateway
      ? null
      : `gateway 必须是字符串（≤${LIMITS.gateway}）`,
};

/** 按码点截断（与 Java 侧按字符数截断的口径对齐，避免把代理对劈开） */
export function truncate(value, limit) {
  const text = value === undefined || value === null ? '' : String(value);
  if (limit === undefined || limit === null) return text;
  const chars = Array.from(text);
  return chars.length <= limit ? text : chars.slice(0, limit).join('');
}

/**
 * 校验单条上报记录
 * @param {unknown} row
 * @returns {{ok: true, reason?: undefined} | {ok: false, reason: string}}
 */
export function validateShop(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    return { ok: false, reason: '记录必须是对象' };
  }
  for (const [field, check] of Object.entries(FIELD_VALIDATORS)) {
    const reason = check(row[field]);
    if (reason) return { ok: false, reason: `${field}: ${reason}` };
  }
  for (const [field, check] of Object.entries(OPTIONAL_VALIDATORS)) {
    if (row[field] === undefined || row[field] === null) continue;
    const reason = check(row[field]);
    if (reason) return { ok: false, reason: `${field}: ${reason}` };
  }
  return { ok: true };
}

/**
 * 过滤并规范化心跳键：只收 32 位 hex（大小写都收），去重后小写化
 * @param {unknown} keys
 * @returns {string[]}
 */
export function normalizeHeartbeatKeys(keys) {
  if (!Array.isArray(keys)) return [];
  const seen = new Set();
  const out = [];
  for (const key of keys) {
    if (typeof key !== 'string') continue;
    if (!/^[0-9a-f]{32}$/i.test(key)) continue;
    const lower = key.toLowerCase();
    if (seen.has(lower)) continue;
    seen.add(lower);
    out.push(lower);
  }
  return out;
}
