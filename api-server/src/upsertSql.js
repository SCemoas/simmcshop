/**
 * SQL 构造（与 mod 直连时的 `DatabaseReporter.INSERT_SQL` 逐项对齐）
 *
 * 抽成纯函数模块的原因：这里最容易出「列名/列序写错」这种只有
 * 真库才暴露的错误，而用纯函数就能在 `node --test` 里把 SQL 文本钉死，
 * 不需要连 MySQL、也不需要装驱动（驱动只在 mysqlWriter 里懒加载）。
 *
 * 三条不能动的语义（见 CONTRACT.md 第 5 节）：
 *   1. `first_seen_at` **不进** UPDATE 列 —— 已存在的行必须保留首次收录时间；
 *      写法是 `first_seen_at = first_seen_at`（MySQL 5.7 下 `VALUES(first_seen_at)` 会取到新值）
 *   2. `location_key` 由**服务端**算，不信客户端提交的键
 *   3. MySQL 5.7：不用 CTE / 窗口函数，`ON DUPLICATE KEY UPDATE` 用 `VALUES()` 语法
 */

import { LIMITS, truncate } from './validate.js';
import { locationKey } from './locationKey.js';

/**
 * 19 列，顺序即 INSERT 的值顺序
 *
 * 与 `DatabaseReporter.INSERT_SQL` 的列集一致；加列必须同时改
 * mod 的 INSERT_SQL、`database/migrate-v2.sql` 与 CONTRACT.md。
 */
export const COLUMNS = [
  'seen_at',
  'first_seen_at',
  'last_seen_at',
  'owner',
  'type',
  'quantity',
  'item',
  'price',
  'price_raw',
  'world',
  'x',
  'y',
  'z',
  'side',
  'server',
  'raw',
  'land',
  'gateway',
  'location_key',
];

/**
 * 冲突时要覆盖的列（顺序与站点时代一致：先业务列，再 last_seen_at，最后 first_seen_at 自指）
 *
 * `first_seen_at` 之外都是「以最新一次扫描为准」的列。
 */
export const UPDATE_COLUMNS = [
  'seen_at',
  'owner',
  'type',
  'quantity',
  'item',
  'price',
  'price_raw',
  'world',
  'x',
  'y',
  'z',
  'side',
  'server',
  'raw',
  'land',
  'gateway',
  'last_seen_at',
];

/** 单条 INSERT 最多塞多少行（列数多，行数太大会顶到 max_allowed_packet） */
export const INSERT_BATCH = 200;

/** 心跳每批 UPDATE 的键数（与 mod 端 `DatabaseReporter.HEARTBEAT_CHUNK` 一致） */
export const HEARTBEAT_CHUNK = 500;

/** 表名白名单校验（表名会拼进 SQL，不能来自请求） */
export function assertSafeTable(table) {
  if (!/^[A-Za-z0-9_]{1,64}$/.test(String(table ?? ''))) {
    throw new Error(`非法表名：${table}`);
  }
  return table;
}

/**
 * 把一条上报记录摊成 19 个占位符参数
 *
 * @param {object} row 已通过 validateShop 的记录
 * @param {string} firstSeenAt 新行使用的首次收录时间（`YYYY-MM-DD HH:MM:SS`）
 * @returns {Array<string|number>} 长度等于 COLUMNS.length
 */
export function toRowParams(row, firstSeenAt) {
  return [
    truncate(row.seenAt, 19),
    truncate(firstSeenAt, 19),
    truncate(row.lastSeenAt, 19),
    truncate(row.owner, LIMITS.owner),
    truncate(row.type, 16),
    row.quantity,
    truncate(row.item, LIMITS.item),
    row.price,
    truncate(row.priceRaw, LIMITS.priceRaw),
    truncate(row.world, LIMITS.world),
    row.x,
    row.y,
    row.z,
    truncate(row.side, 16),
    truncate(row.server, LIMITS.server),
    truncate(row.raw, LIMITS.raw),
    truncate(row.land, LIMITS.land),
    truncate(row.gateway, LIMITS.gateway),
    locationKey(row),
  ];
}

/**
 * 构造多行 upsert 语句
 *
 * @param {string} table 已校验的表名
 * @param {number} rowCount 本批行数（≥1）
 * @returns {string}
 */
export function buildUpsertSql(table, rowCount) {
  assertSafeTable(table);
  if (!Number.isInteger(rowCount) || rowCount < 1) {
    throw new Error(`非法行数：${rowCount}`);
  }
  const tuple = `(${COLUMNS.map(() => '?').join(',')})`;
  const values = Array.from({ length: rowCount }, () => tuple).join(', ');
  const updates = [
    ...UPDATE_COLUMNS.map((c) => `  ${c} = VALUES(${c})`),
    // 第 1 条语义：已存在的行保留首次收录时间
    '  first_seen_at = first_seen_at',
  ].join(',\n');
  return (
    `INSERT INTO ${table}\n` +
    `  (${COLUMNS.join(', ')})\n` +
    `VALUES ${values}\n` +
    `ON DUPLICATE KEY UPDATE\n${updates}`
  );
}

/**
 * 构造心跳语句
 * @param {string} table
 * @param {number} keyCount
 * @returns {string}
 */
export function buildHeartbeatSql(table, keyCount) {
  assertSafeTable(table);
  if (!Number.isInteger(keyCount) || keyCount < 1) {
    throw new Error(`非法键数：${keyCount}`);
  }
  const placeholders = Array.from({ length: keyCount }, () => '?').join(',');
  return `UPDATE ${table} SET last_seen_at = ? WHERE location_key IN (${placeholders})`;
}
