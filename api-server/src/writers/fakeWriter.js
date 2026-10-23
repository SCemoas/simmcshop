/**
 * 内存写入器（不连数据库）
 *
 * 两个用途：
 *   1. 自动化测试：`node --test` 不装 mysql2 也能跑完整链路（验签 → 校验 → 写入语义）
 *   2. 排查用：`DB_DRIVER=fake node server.js` 起一个只打印不落库的服务，
 *      用来验证 nginx / 证书 / 客户端密钥是否正确，**不碰生产库**
 *
 * 语义与 mysqlWriter 保持一致（upsert 保留 first_seen_at、心跳按 key 命中计数）。
 */

import { locationKey } from '../locationKey.js';

/**
 * @param {{failWith?: Error|null, firstSeenAt?: () => string}} [options]
 */
export function createFakeWriter(options = {}) {
  /** @type {Map<string, any>} */
  const rows = new Map();
  const calls = { upsert: [], heartbeat: [] };
  const getTime = options.firstSeenAt ?? (() => '2026-01-01 00:00:00');

  function maybeFail() {
    if (options.failWith) throw options.failWith;
  }

  return {
    dialect: 'fake',
    rows,
    calls,

    /**
     * 写入一批记录
     * @param {Array<object>} batch
     * @returns {Promise<number>} 写入的行数
     */
    async upsertRows(batch) {
      maybeFail();
      calls.upsert.push(batch);
      for (const row of batch) {
        const key = locationKey(row);
        const existing = rows.get(key);
        rows.set(key, {
          location_key: key,
          first_seen_at: existing?.first_seen_at ?? getTime(),
          seen_at: row.seenAt,
          last_seen_at: row.lastSeenAt,
          owner: row.owner,
          type: row.type,
          quantity: row.quantity,
          item: row.item,
          price: row.price,
          price_raw: row.priceRaw ?? '',
          world: row.world,
          x: row.x,
          y: row.y,
          z: row.z,
          side: row.side,
          server: row.server,
          raw: row.raw ?? '',
          land: row.land ?? '',
          gateway: row.gateway ?? '',
        });
      }
      return batch.length;
    },

    /**
     * 心跳：只刷新已存在行的 last_seen_at
     * @param {string[]} keys
     * @param {string} at
     * @returns {Promise<number>} 命中行数
     */
    async touchLastSeen(keys, at) {
      maybeFail();
      calls.heartbeat.push({ keys: [...keys], at });
      let touched = 0;
      for (const key of keys) {
        const row = rows.get(String(key).toLowerCase());
        if (!row) continue;
        row.last_seen_at = at;
        touched += 1;
      }
      return touched;
    },

    async ping() {
      return { ok: !options.failWith, dialect: 'fake' };
    },

    async close() {
      /* 无资源 */
    },
  };
}
