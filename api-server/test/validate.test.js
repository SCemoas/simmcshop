/**
 * 字段校验与心跳键规范化
 *
 * 每条 `assert` 都对应一条真实的坏数据来源：
 * 解析器把价格读成 Infinity、牌子被改坏、玩家名带颜色码、1.21 的坐标溢出……
 * 规矩是「坏记录只丢这一条，不能污染整批」。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { makeShop } from '../src/testing/harness.js';
import { LIMITS, MAX_PRICE, normalizeHeartbeatKeys, truncate, validateShop } from '../src/validate.js';

test('合法记录通过校验（含可选字段缺省）', () => {
  assert.deepEqual(validateShop(makeShop()), { ok: true });
  const minimal = makeShop();
  delete minimal.raw;
  delete minimal.land;
  delete minimal.gateway;
  assert.deepEqual(validateShop(minimal), { ok: true });
});

test('非对象、数组、null 一律拒绝', () => {
  for (const bad of [null, undefined, 42, 'text', [], [makeShop()]]) {
    const verdict = validateShop(bad);
    assert.equal(verdict.ok, false, `${JSON.stringify(bad)} 应被拒绝`);
    assert.equal(verdict.reason, '记录必须是对象');
  }
});

test('时间串必须严格是 YYYY-MM-DD HH:MM:SS（不接受 ISO / 无秒 / 时区后缀）', () => {
  for (const bad of ['2026-10-20T18:00:00', '2026-10-20 18:00', '2026-10-20 18:00:00Z', '2026/10/20 18:00:00', '']) {
    const verdict = validateShop(makeShop({ seenAt: bad }));
    assert.equal(verdict.ok, false, `${bad} 应被拒绝`);
    assert.match(verdict.reason, /^seenAt: /);
  }
  assert.equal(validateShop(makeShop({ lastSeenAt: '2026-10-20' })).ok, false);
});

test('owner / item / world / server 必须非空', () => {
  assert.equal(validateShop(makeShop({ owner: '' })).ok, false);
  assert.equal(validateShop(makeShop({ item: '' })).ok, false);
  assert.equal(validateShop(makeShop({ world: '' })).ok, false);
  assert.equal(validateShop(makeShop({ server: '' })).ok, false);
});

test('type / side 只认 ENUM 里的值', () => {
  for (const type of ['sell', 'buy', 'sold_out', 'no_space']) {
    assert.equal(validateShop(makeShop({ type })).ok, true, type);
  }
  for (const type of ['SELL', 'soldout', '', null, 'sold-out']) {
    assert.equal(validateShop(makeShop({ type })).ok, false, String(type));
  }
  assert.equal(validateShop(makeShop({ side: 'back' })).ok, true);
  for (const side of ['FRONT', 'left', '', null]) {
    assert.equal(validateShop(makeShop({ side })).ok, false, String(side));
  }
});

test('quantity 必须整数且 ≥ -1（-1 表示无限）', () => {
  assert.equal(validateShop(makeShop({ quantity: -1 })).ok, true);
  assert.equal(validateShop(makeShop({ quantity: 0 })).ok, true);
  for (const quantity of [-2, 1.5, '64', null, Number.NaN]) {
    assert.equal(validateShop(makeShop({ quantity })).ok, false, String(quantity));
  }
});

test('price 必须是有限数且 |price| < 1e15', () => {
  assert.equal(validateShop(makeShop({ price: 0 })).ok, true);
  assert.equal(validateShop(makeShop({ price: -12.5 })).ok, true);
  assert.equal(validateShop(makeShop({ price: 1e15 })).ok, false);
  assert.equal(validateShop(makeShop({ price: -1e15 })).ok, false);
  assert.equal(validateShop(makeShop({ price: 999e12 })).ok, true);
  for (const price of [Number.POSITIVE_INFINITY, Number.NaN, '12.5', null]) {
    assert.equal(validateShop(makeShop({ price })).ok, false, String(price));
  }
  assert.equal(MAX_PRICE, 1e15);
});

test('坐标必须是整数（小数、字符串、NaN 都拒绝）', () => {
  for (const field of ['x', 'y', 'z']) {
    for (const value of [1.5, '1', null, Number.NaN]) {
      assert.equal(validateShop(makeShop({ [field]: value })).ok, false, `${field}=${value}`);
    }
    assert.equal(validateShop(makeShop({ [field]: -30000000 })).ok, true);
  }
});

test('列宽上限与 mod 端 ColumnLimits 一致', () => {
  assert.equal(LIMITS.owner, 64);
  assert.equal(LIMITS.item, 255);
  assert.equal(LIMITS.priceRaw, 255);
  assert.equal(LIMITS.raw, 1024);
  assert.equal(LIMITS.land, 64);
  assert.equal(LIMITS.gateway, 192);
  assert.equal(LIMITS.world, 128);
  assert.equal(LIMITS.server, 255);
});

test('列宽：正好等于上限通过，超一个字符拒绝', () => {
  const cases = [
    ['owner', LIMITS.owner],
    ['item', LIMITS.item],
    ['priceRaw', LIMITS.priceRaw],
    ['raw', LIMITS.raw],
    ['land', LIMITS.land],
    ['gateway', LIMITS.gateway],
    ['world', LIMITS.world],
    ['server', LIMITS.server],
  ];
  for (const [field, limit] of cases) {
    assert.equal(validateShop(makeShop({ [field]: 'a'.repeat(limit) })).ok, true, `${field} 上限内`);
    assert.equal(validateShop(makeShop({ [field]: 'a'.repeat(limit + 1) })).ok, false, `${field} 超限`);
  }
});

test('可选字段：缺省或 null 视为空，给了就必须是字符串且不超限', () => {
  assert.equal(validateShop(makeShop({ land: null, gateway: null, raw: null })).ok, true);
  const omitted = { ...makeShop() };
  omitted.land = undefined;
  omitted.gateway = undefined;
  omitted.raw = undefined;
  assert.equal(validateShop(omitted).ok, true);
  assert.equal(validateShop(makeShop({ land: 123 })).ok, false);
  assert.equal(validateShop(makeShop({ gateway: {} })).ok, false);
});

test('truncate：按码点截断，不劈开代理对', () => {
  assert.equal(truncate('abcdef', 3), 'abc');
  assert.equal(truncate('abcdef', 99), 'abcdef');
  assert.equal(truncate(null, 3), '');
  // 一个 emoji 是一个码点（两个 UTF-16 单元），截 1 个应保留完整 emoji
  assert.equal(truncate('🙂🙂🙂', 1), '🙂');
  assert.equal(Array.from(truncate('🙂🙂🙂', 2)).length, 2);
  assert.equal(truncate('abc', 0), '');
});

test('心跳键：只收 32 位 hex、小写化、去重、保序、丢弃非法值', () => {
  const a = '7db68f2a217a7bf33438f71dcb0d10e7';
  const b = 'CFFC4FD4C20CB5DDA498E426717AD7A3';
  const result = normalizeHeartbeatKeys([a, b, a, b.toLowerCase(), 'not-a-key', '', 42, null, 'g'.repeat(32)]);
  assert.deepEqual(result, [a, 'cffc4fd4c20cb5dda498e426717ad7a3']);
});

test('心跳键：非数组一律当空', () => {
  for (const bad of [null, undefined, 'abc', 42, { length: 1 }]) {
    assert.deepEqual(normalizeHeartbeatKeys(bad), [], String(bad));
  }
});
