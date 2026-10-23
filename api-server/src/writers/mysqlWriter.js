/**
 * MySQL 写入器
 *
 * 直接复用 mod 直连时代的 SQL 语义（见 `src/upsertSql.js`），
 * 但与 mod 直连相比有两点结构差异：
 *   - 用 `mysql2/promise` 连接池（4 条），多客户端上报不会互相阻塞
 *   - 驱动是**懒加载**的：没装依赖时只有真正需要连库才报错，
 *     因此 `node --test` 与「只做验签联调」都不需要 `npm install`
 *
 * 目标库是 MySQL 5.7（不要用 CTE / 窗口函数）。
 */

import { buildHeartbeatSql, buildUpsertSql, HEARTBEAT_CHUNK, INSERT_BATCH, toRowParams } from '../upsertSql.js';

/**
 * 创建 MySQL 写入器
 *
 * @param {{host:string, port:number, user:string, password:string, database:string,
 *          table:string, poolSize:number}} dbConfig
 * @param {{log?: {info:Function, warn:Function, error:Function}, now?: () => string}} [deps]
 */
export async function createMysqlWriter(dbConfig, deps = {}) {
  const log = deps.log ?? { info() {}, warn() {}, error() {} };
  const clock = deps.now ?? (() => new Date().toISOString().slice(0, 19).replace('T', ' '));

  let mysql;
  try {
    mysql = await import('mysql2/promise');
  } catch (err) {
    throw new Error(
      '缺少依赖 mysql2：请在 api-server 目录执行 `npm install --omit=dev`' +
        `（原始错误：${err?.message ?? err}）`,
    );
  }

  const pool = mysql.createPool({
    host: dbConfig.host,
    port: dbConfig.port,
    user: dbConfig.user,
    password: dbConfig.password,
    database: dbConfig.database,
    connectionLimit: dbConfig.poolSize,
    waitForConnections: true,
    queueLimit: 0,
    charset: 'utf8mb4',
    // DATETIME 以字符串返回，避免驱动按本地时区转 Date 再格式化带来的偏移
    dateStrings: true,
    connectTimeout: 10000,
    enableKeepAlive: true,
  });

  return {
    dialect: 'mysql',

    /**
     * 写入一批记录
     *
     * 超过 `INSERT_BATCH` 会自动分批（列多、单条 SQL 过长会顶到 max_allowed_packet）。
     * 返回「提交成功的行数」，某批抛错则整体抛错 ——
     * 由调用方回 500，mod 端会把整批留在 Outbox 里等下次重传。
     *
     * @param {Array<object>} rows
     * @returns {Promise<number>}
     */
    async upsertRows(rows) {
      const firstSeenAt = clock();
      let saved = 0;
      for (let i = 0; i < rows.length; i += INSERT_BATCH) {
        const batch = rows.slice(i, i + INSERT_BATCH);
        const sql = buildUpsertSql(dbConfig.table, batch.length);
        const params = [];
        for (const row of batch) params.push(...toRowParams(row, firstSeenAt));
        await pool.query(sql, params);
        saved += batch.length;
      }
      return saved;
    },

    /**
     * 心跳：把一批 location_key 的 last_seen_at 刷成 at
     * @param {string[]} keys 已去重、小写化
     * @param {string} at `YYYY-MM-DD HH:MM:SS`
     * @returns {Promise<number>} 命中行数
     */
    async touchLastSeen(keys, at) {
      let touched = 0;
      for (let i = 0; i < keys.length; i += HEARTBEAT_CHUNK) {
        const chunk = keys.slice(i, i + HEARTBEAT_CHUNK);
        const sql = buildHeartbeatSql(dbConfig.table, chunk.length);
        const [result] = await pool.query(sql, [at, ...chunk]);
        touched += Number(result?.affectedRows ?? 0);
      }
      return touched;
    },

    /** 连通性检查（health 端点用） */
    async ping() {
      await pool.query('SELECT 1');
      return { ok: true, dialect: 'mysql' };
    },

    async close() {
      await pool.end();
    },
  };
}
