/**
 * 端到端：真实 node:http 监听 + 真实 HMAC 验签 + 假写入器
 *
 * 这一层测的是「客户端真发的请求，服务端真能接住」——
 * mod 端 `ApiClient` 的错误分诊（401/403/429/5xx）全靠这些状态码，
 * 所以状态码与响应 JSON 形状在这里逐条钉死。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { createFakeWriter } from '../src/writers/fakeWriter.js';
import { locationKey } from '../src/locationKey.js';
import { SIGNATURE_HEADER } from '../src/auth.js';
import { SERVICE_NAME, SERVICE_VERSION } from '../src/version.js';
import {
  makePayload,
  makeShop,
  postSigned,
  SECRET,
  sendRequest,
  signPayload,
  startTestServer,
} from '../src/testing/harness.js';

/** 起服务，测试结束自动关 */
async function withServer(options, fn) {
  const server = await startTestServer(options);
  try {
    return await fn(server);
  } finally {
    await server.close();
  }
}

test('上报：合法批次写入，坏记录只计 rejected 不报 500', async () => {
  await withServer({}, async (server) => {
    const good = makeShop();
    const payload = makePayload({
      shops: [good, makeShop({ owner: 'Alex', x: 200 }), { ...makeShop(), type: 'discount' }],
    });
    const res = await postSigned(server.port, payload);

    assert.equal(res.status, 200);
    assert.equal(res.headers['content-type'], 'application/json; charset=utf-8');
    assert.equal(res.json.ok, true);
    assert.equal(res.json.saved, 2);
    assert.equal(res.json.rejected, 1);
    assert.equal(res.json.heartbeated, 0);
    assert.match(res.json.time, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    assert.equal(server.writer.rows.size, 2);
    assert.equal(server.writer.rows.get(locationKey(good)).owner, 'Steve');
    assert.equal(server.stats.rejectedRows, 1);
  });
});

test('上报：同一位置重复上报只更新一行，且保留 first_seen_at', async () => {
  const writer = createFakeWriter({ firstSeenAt: () => '2026-01-01 00:00:00' });
  await withServer({ writer }, async (server) => {
    const first = makeShop({ seenAt: '2026-10-20 18:00:00', lastSeenAt: '2026-10-20 18:00:00' });
    await postSigned(server.port, makePayload({ shops: [first] }));
    const second = makeShop({
      seenAt: '2026-10-21 09:00:00',
      lastSeenAt: '2026-10-21 09:00:00',
      price: 99.9,
      priceRaw: '99.9',
    });
    const res = await postSigned(server.port, makePayload({ shops: [second] }));

    assert.equal(res.json.saved, 1);
    assert.equal(server.writer.rows.size, 1, '同一 location_key 不得新增行');
    const row = server.writer.rows.get(locationKey(first));
    assert.equal(row.price, 99.9, '业务列以最新扫描为准');
    assert.equal(row.seen_at, '2026-10-21 09:00:00');
    assert.equal(row.first_seen_at, '2026-01-01 00:00:00', '首次收录时间必须保留');
  });
});

test('心跳：只刷新已存在行的 last_seen_at，非法/重复键被丢弃', async () => {
  await withServer({}, async (server) => {
    const shop = makeShop();
    await postSigned(server.port, makePayload({ shops: [shop] }));
    const key = locationKey(shop);
    const unknown = 'a'.repeat(32);

    const res = await postSigned(
      server.port,
      makePayload({
        shops: [],
        heartbeatKeys: [key, key.toUpperCase(), unknown, 'not-a-key', 42],
      }),
    );

    assert.equal(res.status, 200);
    assert.equal(res.json.saved, 0);
    assert.equal(res.json.heartbeated, 1, '只有已知位置键算命中');
    assert.deepEqual(server.writer.calls.heartbeat.at(-1).keys, [key, unknown]);
    assert.match(server.writer.rows.get(key).last_seen_at, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });
});

test('心跳包只带心跳键（mod 端空闲时的维持请求）也能成功', async () => {
  await withServer({}, async (server) => {
    const res = await postSigned(server.port, makePayload({ shops: [], heartbeatKeys: [] }));
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { ok: true, saved: 0, heartbeated: 0, rejected: 0, time: res.json.time });
  });
});

test('鉴权：签名不对 → 401 且不写库', async () => {
  await withServer({}, async (server) => {
    const res = await postSigned(server.port, makePayload({ shops: [makeShop()] }), {
      secret: 'wrong-secret-0123456789',
    });
    assert.equal(res.status, 401);
    assert.equal(res.json.ok, false);
    assert.match(res.json.message, /签名校验失败/);
    assert.equal(server.writer.rows.size, 0);
    assert.equal(server.stats.authFailures, 1);
  });
});

test('鉴权：签名后篡改请求体 → 401（验签基于原始字节）', async () => {
  await withServer({}, async (server) => {
    const signed = signPayload(makePayload({ shops: [makeShop()] }));
    const tampered = Buffer.from(signed.raw.toString('utf8').replace('Steve', 'Mallory'), 'utf8');
    const res = await sendRequest(server.port, {
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': tampered.length,
        [SIGNATURE_HEADER]: signed.signature,
      },
      body: tampered,
    });
    assert.equal(res.status, 401);
    assert.equal(server.writer.rows.size, 0);
  });
});

test('鉴权：缺签名头 → 401；空体 → 400', async () => {
  await withServer({}, async (server) => {
    const noHeader = await sendRequest(server.port, {
      headers: { 'Content-Type': 'application/json', 'Content-Length': 2 },
      body: '{}',
    });
    assert.equal(noHeader.status, 401);
    assert.match(noHeader.json.message, /缺少 X-ShopSign-Signature/);

    const empty = await sendRequest(server.port, {
      headers: { 'Content-Type': 'application/json', 'Content-Length': 0, [SIGNATURE_HEADER]: 'x' },
    });
    assert.equal(empty.status, 400);
  });
});

test('鉴权：时间戳偏差超 5 分钟 → 401（两个方向）', async () => {
  await withServer({}, async (server) => {
    const old = await postSigned(server.port, makePayload({ timestamp: Date.now() - 301000 }));
    assert.equal(old.status, 401);
    assert.match(old.json.message, /时间戳超出允许范围/);

    const future = await postSigned(server.port, makePayload({ timestamp: Date.now() + 301000 }));
    assert.equal(future.status, 401);
  });
});

test('鉴权：缺 timestamp → 400', async () => {
  await withServer({}, async (server) => {
    const res = await postSigned(server.port, { shops: [] });
    assert.equal(res.status, 400);
    assert.match(res.json.message, /timestamp/);
  });
});

test('IP 白名单：不在名单内 → 403；在名单内放行', async () => {
  await withServer({ env: { INGEST_ALLOWED_IPS: '10.9.8.7' } }, async (server) => {
    const denied = await postSigned(server.port, makePayload());
    assert.equal(denied.status, 403);
    assert.match(denied.json.message, /不在白名单/);

    // 回环直连时 clientIp 是 127.0.0.1，加进白名单后应放行
    const allowed = await postSigned(server.port, makePayload(), {
      headers: { 'X-Forwarded-For': '10.9.8.7' },
    });
    assert.equal(allowed.status, 200);
  });
});

test('限流：超过每分钟上限 → 429 并带 Retry-After', async () => {
  await withServer({ env: { INGEST_RATE_LIMIT: '3', INGEST_RATE_WINDOW_MS: '60000' } }, async (server) => {
    for (let i = 0; i < 3; i += 1) {
      const ok = await postSigned(server.port, makePayload());
      assert.equal(ok.status, 200, `第 ${i + 1} 次应放行`);
    }
    const limited = await postSigned(server.port, makePayload());
    assert.equal(limited.status, 429);
    assert.equal(limited.json.ok, false);
    assert.ok(Number(limited.headers['retry-after']) >= 1);
    assert.equal(server.stats.rateLimited, 1);
  });
});

test('上限：商店条数超限 → 400（整批拒绝，不当成 rejected）', async () => {
  await withServer({ env: { INGEST_MAX_SHOPS: '2' } }, async (server) => {
    const res = await postSigned(server.port, makePayload({ shops: [makeShop(), makeShop({ x: 1 }), makeShop({ x: 2 })] }));
    assert.equal(res.status, 400);
    assert.match(res.json.message, /商店数超过上限/);
    assert.equal(server.writer.rows.size, 0);
  });
});

test('上限：心跳键条数超限 → 400', async () => {
  await withServer({ env: { INGEST_MAX_HEARTBEAT_KEYS: '2' } }, async (server) => {
    const res = await postSigned(server.port, makePayload({ heartbeatKeys: ['a'.repeat(32), 'b'.repeat(32), 'c'.repeat(32)] }));
    assert.equal(res.status, 400);
    assert.match(res.json.message, /心跳键数超过上限/);
  });
});

test('上限：请求体超限 → 413', async () => {
  await withServer({ env: { INGEST_MAX_BODY_BYTES: '1024' } }, async (server) => {
    const big = makePayload({ shops: [makeShop({ raw: 'x'.repeat(4000) })] });
    const res = await postSigned(server.port, big);
    assert.equal(res.status, 413);
    assert.match(res.json.message, /请求体超过上限/);
  });
});

test('方法：GET /api/ingest/shops → 405 且声明 Allow: POST', async () => {
  await withServer({}, async (server) => {
    const res = await sendRequest(server.port, { method: 'GET', path: '/api/ingest/shops' });
    assert.equal(res.status, 405);
    assert.equal(res.headers.allow, 'POST');
  });
});

test('路由：未知端点 → 404；接口关闭 → 404', async () => {
  await withServer({}, async (server) => {
    const res = await sendRequest(server.port, { path: '/api/ingest/nope', body: '{}' });
    assert.equal(res.status, 404);
  });
  await withServer({ env: { INGEST_ENABLED: 'false' } }, async (server) => {
    const res = await postSigned(server.port, makePayload());
    assert.equal(res.status, 404);
    assert.match(res.json.message, /未启用/);
  });
});

test('/healthz：不验签的运维探活，只回服务名与版本', async () => {
  await withServer({}, async (server) => {
    const res = await sendRequest(server.port, { method: 'GET', path: '/healthz' });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { ok: true, service: SERVICE_NAME, version: SERVICE_VERSION });
  });
});

test('自检端点：验签后返回服务信息与数据库状态', async () => {
  await withServer({}, async (server) => {
    const res = await postSigned(server.port, makePayload(), { path: '/api/ingest/health' });
    assert.equal(res.status, 200);
    assert.equal(res.json.ok, true);
    assert.equal(res.json.service, SERVICE_NAME);
    assert.deepEqual(res.json.database, { ok: true });
    assert.match(res.json.time, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });
});

test('自检端点：数据库不可用时仍 200，但 database.ok=false（别让 mod 以为密钥错了）', async () => {
  const writer = createFakeWriter();
  writer.ping = async () => {
    throw new Error('connect ECONNREFUSED 127.0.0.1:3306');
  };
  await withServer({ writer }, async (server) => {
    const res = await postSigned(server.port, makePayload(), { path: '/api/ingest/health' });
    assert.equal(res.status, 200);
    assert.equal(res.json.ok, true);
    assert.equal(res.json.database.ok, false);
    assert.match(res.json.database.message, /ECONNREFUSED/);
  });
});

test('写库失败 → 500 ok:false（mod 端会保留整批并退避重试）', async () => {
  const writer = createFakeWriter({ failWith: new Error('ER_LOCK_WAIT_TIMEOUT') });
  await withServer({ writer }, async (server) => {
    const res = await postSigned(server.port, makePayload({ shops: [makeShop()] }));
    assert.equal(res.status, 500);
    assert.equal(res.json.ok, false);
    assert.match(res.json.message, /服务端写入失败/);
    assert.ok(server.logs.some((line) => line.includes('ER_LOCK_WAIT_TIMEOUT')), '必须留下可排查的日志');
  });
});

test('未签名的健康检查不会泄露内部信息', async () => {
  await withServer({}, async (server) => {
    const res = await sendRequest(server.port, { path: '/api/ingest/health', body: '{}' });
    assert.equal(res.status, 401);
    assert.equal(res.json.database, undefined);
  });
});
