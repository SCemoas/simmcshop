/**
 * SQL 构造
 *
 * 为什么值得单独测：这一层出错只有真库才会报（列数不匹配 / 列序错位 / 少一个占位符），
 * 而纯函数可以在 `node --test` 里把 SQL 文本与参数数组直接钉死，不碰数据库、不装驱动。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assertSafeTable,
  buildHeartbeatSql,
  buildUpsertSql,
  COLUMNS,
  HEARTBEAT_CHUNK,
  INSERT_BATCH,
  toRowParams,
  UPDATE_COLUMNS,
} from '../src/upsertSql.js';
import { locationKey } from '../src/locationKey.js';
import { LIMITS } from '../src/validate.js';
import { makeShop } from '../src/testing/harness.js';

test('列集与列序必须与 mod 端 INSERT_SQL / migrate-v2.sql 一致（19 列）', () => {
  assert.deepEqual(COLUMNS, [
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
  ]);
  assert.equal(COLUMNS.length, 19);
});

test('UPDATE 列不包含 first_seen_at（已存在的行必须保留首次收录时间）', () => {
  assert.ok(UPDATE_COLUMNS.includes('last_seen_at'));
  assert.ok(!UPDATE_COLUMNS.includes('first_seen_at'));
  assert.equal(new Set(UPDATE_COLUMNS).size, UPDATE_COLUMNS.length, 'UPDATE 列不得重复');
});

test('upsert 语句：19 个占位符/行，first_seen_at 自指，绝不出现 VALUES(first_seen_at)', () => {
  const sql = buildUpsertSql('shop_signs', 1);
  assert.match(sql, /^INSERT INTO shop_signs\n {2}\(/);
  assert.equal((sql.match(/\?/g) ?? []).length, 19);
  assert.match(sql, /ON DUPLICATE KEY UPDATE/);
  assert.match(sql, /first_seen_at = first_seen_at/);
  assert.ok(!/VALUES\(first_seen_at\)/.test(sql), 'VALUE(first_seen_at) 会覆盖首次收录时间');
  // MySQL 5.7 兼容：不得出现 CTE / 窗口函数
  assert.ok(!/\bWITH\b|\bOVER\s*\(/i.test(sql));
});

test('upsert 语句：多行时占位符数量按行数线性增长', () => {
  const sql = buildUpsertSql('shop_signs', 10);
  assert.equal((sql.match(/\?/g) ?? []).length, 190);
  assert.equal((sql.match(/\(\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?,\?\)/g) ?? []).length, 10);
});

test('upsert 语句：每行 VALUES() 都被更新（含 world/x/y/z/side/server/raw/land/gateway）', () => {
  const sql = buildUpsertSql('tbl', 1);
  for (const column of UPDATE_COLUMNS) {
    assert.ok(sql.includes(`${column} = VALUES(${column})`), `缺少更新 ${column}`);
  }
});

test('表名白名单：拼进 SQL 的表名只能由字母数字下划线组成', () => {
  assert.equal(assertSafeTable('shop_signs'), 'shop_signs');
  for (const bad of ['shop_signs; DROP TABLE x', 'shop signs', 'shop-signs', '', null, 'a.b']) {
    assert.throws(() => assertSafeTable(bad), /非法表名/, String(bad));
    assert.throws(() => buildUpsertSql(bad, 1), /非法表名/, String(bad));
  }
});

test('行数/键数非法时直接抛错（构造 0 行 SQL 一定是上游 bug）', () => {
  for (const bad of [0, -1, 1.5, Number.NaN, '2']) {
    assert.throws(() => buildUpsertSql('shop_signs', bad), /非法行数/, String(bad));
    assert.throws(() => buildHeartbeatSql('shop_signs', bad), /非法键数/, String(bad));
  }
});

test('toRowParams：19 个参数，顺序与 COLUMNS 对应，location_key 由服务端计算', () => {
  const row = makeShop();
  const params = toRowParams(row, '2026-10-20 19:00:00');
  assert.equal(params.length, COLUMNS.length);

  const byColumn = Object.fromEntries(COLUMNS.map((column, i) => [column, params[i]]));
  assert.equal(byColumn.seen_at, '2026-10-20 18:00:00');
  assert.equal(byColumn.first_seen_at, '2026-10-20 19:00:00');
  assert.equal(byColumn.last_seen_at, '2026-10-20 18:00:00');
  assert.equal(byColumn.owner, 'Steve');
  assert.equal(byColumn.type, 'sell');
  assert.equal(byColumn.quantity, 64);
  assert.equal(byColumn.item, 'minecraft:oak_log');
  assert.equal(byColumn.price, 12.5);
  assert.equal(byColumn.price_raw, '12.5');
  assert.equal(byColumn.world, 'minecraft:overworld');
  assert.equal(byColumn.x, 100);
  assert.equal(byColumn.y, 64);
  assert.equal(byColumn.z, -200);
  assert.equal(byColumn.side, 'front');
  assert.equal(byColumn.server, 'play.example.com');
  assert.equal(byColumn.location_key, locationKey(row));
  assert.match(String(byColumn.location_key), /^[0-9a-f]{32}$/);
});

test('toRowParams：无视客户端提交的 locationKey（键一律服务端算）', () => {
  const row = makeShop({ locationKey: 'f'.repeat(32), location_key: 'f'.repeat(32) });
  const params = toRowParams(row, '2026-10-20 19:00:00');
  assert.equal(params[COLUMNS.indexOf('location_key')], locationKey(makeShop()));
});

test('toRowParams：可选字段缺省填空串，超长按列宽截断（不整批失败）', () => {
  const row = makeShop({ raw: 'x'.repeat(LIMITS.raw + 50), land: undefined, gateway: null });
  const params = toRowParams(row, '2026-10-20 19:00:00');
  const byColumn = Object.fromEntries(COLUMNS.map((column, i) => [column, params[i]]));
  assert.equal(Array.from(String(byColumn.raw)).length, LIMITS.raw);
  assert.equal(byColumn.land, '');
  assert.equal(byColumn.gateway, '');
});

test('心跳语句：1 个时间参数 + N 个键占位符', () => {
  const sql = buildHeartbeatSql('shop_signs', 3);
  assert.equal(sql, 'UPDATE shop_signs SET last_seen_at = ? WHERE location_key IN (?,?,?)');
  assert.equal((buildHeartbeatSql('shop_signs', 500).match(/\?/g) ?? []).length, 501);
});

test('批量常量：单批 200 行、心跳 500 键（与 mod 端 HEARTBEAT_CHUNK 一致）', () => {
  assert.equal(INSERT_BATCH, 200);
  assert.equal(HEARTBEAT_CHUNK, 500);
});
