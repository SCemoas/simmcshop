/**
 * 鉴权：签名、定长比较、客户端 IP、IP 白名单、限流、验签时序
 *
 * 这些是「开门」逻辑，一旦错就是两种事故：非法请求被放进来（写坏库），
 * 或合法请求被挡回去（全服玩家的牌子都传不上来）。因此每条分支都要钉住。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import crypto from 'node:crypto';

import {
  clientIp,
  createRateLimiter,
  ipAllowed,
  safeEqualHex,
  signBody,
  SIGNATURE_HEADER,
  verifyRequest,
} from '../src/auth.js';
import { SECRET, makePayload } from '../src/testing/harness.js';

test('签名头名与 mod 端 ApiClient.SIGNATURE_HEADER 一致', () => {
  assert.equal(SIGNATURE_HEADER, 'x-shopsign-signature');
});

test('signBody：HMAC-SHA256 小写 hex，且与独立实现一致', () => {
  const raw = Buffer.from('{"a":1}', 'utf8');
  const expected = crypto.createHmac('sha256', SECRET).update(raw).digest('hex');
  assert.equal(signBody(raw, SECRET), expected);
  assert.match(signBody(raw, SECRET), /^[0-9a-f]{64}$/);
  // 密钥不同 → 签名不同
  assert.notEqual(signBody(raw, SECRET), signBody(raw, `${SECRET}x`));
  // 内容差一个字节 → 签名不同（哪怕 JSON 语义相同）
  assert.notEqual(signBody(raw, SECRET), signBody(Buffer.from('{"a": 1}'), SECRET));
});

test('safeEqualHex：长度不等 / 空值 / 非 hex 都不放行', () => {
  const good = signBody(Buffer.from('{}'), SECRET);
  assert.equal(safeEqualHex(good, good), true);
  assert.equal(safeEqualHex(good, good.toUpperCase()), false); // 大小写敏感（客户端只发小写）
  assert.equal(safeEqualHex(good, `${good}0`), false);
  assert.equal(safeEqualHex(good, ''), false);
  assert.equal(safeEqualHex('', ''), false);
  assert.equal(safeEqualHex(undefined, undefined), false);
  assert.equal(safeEqualHex(good, undefined), false);
});

test('verifyRequest：合法请求返回 payload', () => {
  const payload = makePayload({ shops: [] });
  const raw = Buffer.from(JSON.stringify(payload), 'utf8');
  const verdict = verifyRequest({
    rawBody: raw,
    signature: signBody(raw, SECRET),
    secret: SECRET,
    replayWindowMs: 300000,
    now: payload.timestamp,
  });
  assert.equal(verdict.ok, true);
  assert.deepEqual(verdict.payload.shops, []);
});

test('verifyRequest：未配置密钥 → 500（服务端问题，不是客户端填错）', () => {
  const raw = Buffer.from(JSON.stringify(makePayload()), 'utf8');
  const verdict = verifyRequest({
    rawBody: raw,
    signature: signBody(raw, SECRET),
    secret: '',
    replayWindowMs: 300000,
  });
  assert.equal(verdict.ok, false);
  assert.equal(verdict.status, 500);
  assert.match(verdict.message, /INGEST_SHARED_SECRET/);
});

test('verifyRequest：缺签名头 → 401，空体 → 400', () => {
  const raw = Buffer.from(JSON.stringify(makePayload()), 'utf8');
  assert.equal(
    verifyRequest({ rawBody: raw, signature: undefined, secret: SECRET, replayWindowMs: 300000 }).status,
    401,
  );
  assert.equal(
    verifyRequest({ rawBody: Buffer.alloc(0), signature: 'x', secret: SECRET, replayWindowMs: 300000 }).status,
    400,
  );
});

test('verifyRequest：签名错 → 401（密钥不一致 / 体被改动）', () => {
  const raw = Buffer.from(JSON.stringify(makePayload()), 'utf8');
  const wrong = signBody(raw, 'another-secret-0123456789');
  const verdict = verifyRequest({ rawBody: raw, signature: wrong, secret: SECRET, replayWindowMs: 300000 });
  assert.equal(verdict.status, 401);
  assert.match(verdict.message, /签名校验失败/);
});

test('verifyRequest：签名正确但不是 JSON → 400', () => {
  const raw = Buffer.from('not json', 'utf8');
  const verdict = verifyRequest({
    rawBody: raw,
    signature: signBody(raw, SECRET),
    secret: SECRET,
    replayWindowMs: 300000,
  });
  assert.equal(verdict.status, 400);
  assert.match(verdict.message, /不是合法 JSON/);
});

test('verifyRequest：缺 timestamp → 400；偏差超窗 → 401（两个方向）', () => {
  const noTs = { shops: [] };
  const rawNoTs = Buffer.from(JSON.stringify(noTs), 'utf8');
  const missing = verifyRequest({
    rawBody: rawNoTs,
    signature: signBody(rawNoTs, SECRET),
    secret: SECRET,
    replayWindowMs: 300000,
  });
  assert.equal(missing.status, 400);
  assert.match(missing.message, /timestamp/);

  const now = 1_760_992_000_000;
  const raw = Buffer.from(JSON.stringify({ timestamp: now }), 'utf8');
  const signature = signBody(raw, SECRET);
  // 正好 5 分钟：仍在窗内（用 > 比较，不是 >=）
  assert.equal(verifyRequest({ rawBody: raw, signature, secret: SECRET, replayWindowMs: 300000, now: now + 300000 }).ok, true);
  // 客户端时钟快 / 慢 5 分钟零 1 毫秒：拒绝
  assert.equal(verifyRequest({ rawBody: raw, signature, secret: SECRET, replayWindowMs: 300000, now: now + 300001 }).status, 401);
  assert.equal(verifyRequest({ rawBody: raw, signature, secret: SECRET, replayWindowMs: 300000, now: now - 300001 }).status, 401);
});

test('clientIp：trustProxy 才采信 X-Forwarded-For，并剥掉 ::ffff: 与方括号', () => {
  const req = {
    headers: { 'x-forwarded-for': '203.0.113.9, 10.0.0.1' },
    socket: { remoteAddress: '127.0.0.1' },
  };
  assert.equal(clientIp(req, true), '203.0.113.9');
  assert.equal(clientIp(req, false), '127.0.0.1');
  // 反向代理可能给数组
  assert.equal(clientIp({ headers: { 'x-forwarded-for': ['198.51.100.7'] }, socket: {} }, true), '198.51.100.7');
  // IPv4-mapped IPv6 归一
  assert.equal(clientIp({ headers: {}, socket: { remoteAddress: '::ffff:192.0.2.5' } }, false), '192.0.2.5');
  // 走 IPv6 字面量时去掉方括号
  assert.equal(clientIp({ headers: {}, socket: { remoteAddress: '[::1]' } }, false), '::1');
  assert.equal(clientIp({ headers: {}, socket: {} }, false), '');
});

test('ipAllowed：白名单为空表示不限制，否则精确匹配', () => {
  assert.equal(ipAllowed('1.2.3.4', []), true);
  assert.equal(ipAllowed('1.2.3.4', ['1.2.3.4']), true);
  assert.equal(ipAllowed('1.2.3.5', ['1.2.3.4']), false);
  assert.equal(ipAllowed('1.2.3.4', ['1.2.3.4 ']), false); // 不做模糊匹配
});

test('限流：窗口内放行 limit 次，超出拒绝并给出 Retry-After', () => {
  let time = 1_000_000;
  const limiter = createRateLimiter({ limit: 3, windowMs: 60_000, now: () => time });

  assert.equal(limiter.hit('1.1.1.1').allowed, true);
  assert.equal(limiter.hit('1.1.1.1').allowed, true);
  const third = limiter.hit('1.1.1.1');
  assert.equal(third.allowed, true);
  assert.equal(third.remaining, 0);

  const fourth = limiter.hit('1.1.1.1');
  assert.equal(fourth.allowed, false);
  assert.equal(fourth.retryAfterSec, 60);

  // 别的 IP 不受影响
  assert.equal(limiter.hit('2.2.2.2').allowed, true);

  // 窗口过期后重新计数
  time += 60_001;
  assert.equal(limiter.hit('1.1.1.1').allowed, true);
});

test('限流：Retry-After 随窗口剩余时间递减，最小 1 秒', () => {
  let time = 0;
  const limiter = createRateLimiter({ limit: 1, windowMs: 60_000, now: () => time });
  limiter.hit('1.1.1.1');
  time = 1_000;
  assert.equal(limiter.hit('1.1.1.1').retryAfterSec, 59);
  time = 59_999;
  assert.equal(limiter.hit('1.1.1.1').retryAfterSec, 1);
});

test('限流：桶数量超阈值时清理过期项（防内存增长）', () => {
  let time = 0;
  const limiter = createRateLimiter({ limit: 1, windowMs: 1_000, now: () => time, maxEntries: 3 });
  limiter.hit('a');
  limiter.hit('b');
  limiter.hit('c');
  assert.equal(limiter.size, 3);
  time += 1_001;
  limiter.hit('d'); // 触发清理
  assert.ok(limiter.size <= 3, `清理后应不超过阈值，实际 ${limiter.size}`);
});
