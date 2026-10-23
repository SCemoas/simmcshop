/**
 * 测试脚手架（只被 test/ 使用，不参与生产运行）
 *
 * 为什么放在 src/testing 而不是 test/：`node --test` 会把 test/ 目录下的
 * 每个 .js 都当测试文件跑一遍，放在这里可以避免「脚手架本身被当成一个空测试」。
 */

import http from 'node:http';

import { signBody } from '../auth.js';
import { loadConfig } from '../config.js';
import { createIngestServer } from '../http/server.js';
import { createLogger } from '../log.js';
import { createFakeWriter } from '../writers/fakeWriter.js';

/** 测试用共享密钥（够长，能通过最小长度提醒） */
export const SECRET = 'test-secret-0123456789abcdef';

/** 测试配置的基线环境变量：不连库、端口由 server.listen(0) 随机分配 */
export const BASE_ENV = {
  INGEST_SHARED_SECRET: SECRET,
  DB_USER: 'tester',
  DB_PASSWORD: 'unused-in-tests',
  LOG_LEVEL: 'silent',
};

/**
 * 生成配置（覆盖项为环境变量名）
 * @param {Record<string, string>} [overrides]
 */
export function testConfig(overrides = {}) {
  const config = loadConfig({ ...BASE_ENV, ...overrides });
  if (!config.ok) throw new Error(`测试配置不合法：${config.problems.join('；')}`);
  return config;
}

/**
 * 一条合法的上报记录（与 mod 端 ApiReporter 实际发送的字段一致）
 * @param {Record<string, unknown>} [overrides]
 */
export function makeShop(overrides = {}) {
  return {
    seenAt: '2026-10-20 18:00:00',
    lastSeenAt: '2026-10-20 18:00:00',
    owner: 'Steve',
    type: 'sell',
    quantity: 64,
    item: 'minecraft:oak_log',
    price: 12.5,
    priceRaw: '12.5',
    world: 'minecraft:overworld',
    x: 100,
    y: 64,
    z: -200,
    side: 'front',
    server: 'play.example.com',
    raw: '§6Steve\n出售 64 个\n单价：$12.5',
    land: '',
    gateway: '',
    ...overrides,
  };
}

/**
 * 一个合法的上报包
 * @param {Record<string, unknown>} [overrides]
 */
export function makePayload(overrides = {}) {
  return {
    timestamp: Date.now(),
    client: { version: '2.2.3', server: 'play.example.com' },
    shops: [],
    heartbeatKeys: [],
    ...overrides,
  };
}

/**
 * 序列化 + 签名（模拟 mod 端 ApiClient 的做法：对原始字节签名）
 * @param {object|string} payload
 * @param {string} [secret]
 */
export function signPayload(payload, secret = SECRET) {
  const raw = Buffer.from(typeof payload === 'string' ? payload : JSON.stringify(payload), 'utf8');
  return { raw, signature: signBody(raw, secret) };
}

/**
 * 启动一个真实监听的服务实例（随机端口）
 *
 * @param {{env?: Record<string,string>, clock?: () => number, writer?: object}} [options]
 */
export async function startTestServer(options = {}) {
  const config = testConfig(options.env ?? {});
  const writer = options.writer ?? createFakeWriter();
  /** @type {string[]} */
  const logs = [];
  // 日志进内存，不打到 stdout（避免淹没测试报告）；级别取 debug 才能断言到排查用的错误行
  const log = createLogger({ level: 'debug', sink: (line) => logs.push(line) });
  const instance = createIngestServer({ config, writer, log, clock: options.clock ?? Date.now });

  await new Promise((resolve, reject) => {
    instance.server.once('error', reject);
    instance.server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = instance.server.address();

  return {
    ...instance,
    port,
    writer,
    config,
    logs,
    close: async () => {
      await instance.close();
      await writer.close?.();
    },
  };
}

/**
 * 发一个原始 HTTP 请求
 * @param {number} port
 * @param {{method?: string, path?: string, headers?: Record<string, string|number>,
 *          body?: Buffer|string}} [options]
 */
export function sendRequest(port, options = {}) {
  const { method = 'POST', path = '/api/ingest/shops', headers = {}, body } = options;
  return new Promise((resolve, reject) => {
    let settled = false;
    const req = http.request({ host: '127.0.0.1', port, method, path, headers }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        settled = true;
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try {
          json = text ? JSON.parse(text) : null;
        } catch {
          json = null;
        }
        resolve({ status: res.statusCode, headers: res.headers, text, json });
      });
      res.on('error', reject);
    });
    // 服务端提前回 413 并关连接时，写端会收到 EPIPE。
    // 只要已经拿到响应就忽略这个错误，否则它会把真正的断言变成「未捕获异常」。
    req.on('error', (err) => {
      if (!settled) reject(err);
    });
    if (body !== undefined && body !== null) req.write(body);
    req.end();
  });
}

/**
 * 签名后 POST（默认打到 /api/ingest/shops）
 *
 * @param {number} port
 * @param {object|string} payload
 * @param {{path?: string, secret?: string, signature?: string,
 *          headers?: Record<string, string|number>}} [options]
 */
export async function postSigned(port, payload, options = {}) {
  const { path = '/api/ingest/shops', secret = SECRET, signature, headers = {} } = options;
  const signed = signPayload(payload, secret);
  return sendRequest(port, {
    path,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': signed.raw.length,
      'X-ShopSign-Signature': signature ?? signed.signature,
      ...headers,
    },
    body: signed.raw,
  });
}
