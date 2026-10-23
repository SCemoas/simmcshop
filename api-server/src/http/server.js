/**
 * HTTP 服务（零框架：只用 node:http）
 *
 * 端点（契约见 ./CONTRACT.md）：
 *   POST /api/ingest/shops   上报一批商店记录 + 心跳键
 *   POST /api/ingest/health  自检（同样要验签）
 *   GET  /healthz            运维探活（**不验签**，只回 ok + 版本，不碰数据库）
 *
 * 请求处理顺序（有意为之）：
 *   1. 路由存在性 → 2. 开关 → 3. 按 IP 限流（最便宜，先挡垃圾流量）
 *   → 4. IP 白名单 → 5. 方法必须 POST → 6. 读原始请求体（带上限）
 *   → 7. 验签 + 解析 + 时间戳 → 8. 校验条目 → 9. 写库
 *
 * 为什么 3 在 7 之前：验签要算 HMAC 并解析整包 JSON，被刷时开销更大；
 * 但由于这是「先限流后验签」，日志里的 429 不代表请求可信，仅代表太频繁。
 */

import http from 'node:http';
import { clientIp, createRateLimiter, ipAllowed, SIGNATURE_HEADER, verifyRequest } from '../auth.js';
import { normalizeHeartbeatKeys, validateShop } from '../validate.js';
import { now as localNow } from '../time.js';
import { SERVICE_NAME, SERVICE_VERSION } from '../version.js';

/** 路径 → 处理分支 */
const SHOPS_PATH = '/api/ingest/shops';
const HEALTH_PATH = '/api/ingest/health';
const LIVENESS_PATH = '/healthz';

const JSON_TYPE = 'application/json; charset=utf-8';

/**
 * 读取原始请求体（带上限）
 * @param {http.IncomingMessage} req
 * @param {number} limit
 * @returns {Promise<Buffer>}
 */
function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let settled = false;
    const fail = (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    };
    req.on('data', (chunk) => {
      if (settled) return;
      size += chunk.length;
      if (size > limit) {
        fail(Object.assign(new Error(`请求体超过上限 ${limit} 字节`), { code: 'TOO_LARGE' }));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => fail(err));
    req.on('aborted', () => fail(Object.assign(new Error('客户端中断连接'), { code: 'ABORTED' })));
  });
}

/**
 * 写 JSON 响应
 * @param {http.ServerResponse} res
 * @param {number} status
 * @param {object} body
 * @param {Record<string,string>} [headers]
 */
function sendJson(res, status, body, headers = {}) {
  if (res.writableEnded) return;
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': JSON_TYPE,
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(text);
}

/**
 * 创建服务实例（不 listen）
 *
 * @param {{
 *   config: ReturnType<import('../config.js').loadConfig>,
 *   writer: {upsertRows: Function, touchLastSeen: Function, ping?: Function},
 *   log: {info: Function, warn: Function, error: Function, debug: Function},
 *   clock?: () => number,
 * }} deps
 * @returns {{server: http.Server, close: () => Promise<void>, stats: object, limiter: object}}
 */
export function createIngestServer(deps) {
  const { config, writer, log } = deps;
  const clock = deps.clock ?? Date.now;
  const limiter = createRateLimiter({
    limit: config.ingest.rateLimit,
    windowMs: config.ingest.rateWindowMs,
    now: clock,
  });

  const stats = {
    requests: 0,
    authFailures: 0,
    rateLimited: 0,
    rejectedRows: 0,
    savedRows: 0,
    heartbeatedRows: 0,
  };

  /**
   * 验签：失败时直接回错误，成功返回 payload
   * @returns {object|null}
   */
  function authenticate(req, res, rawBody, ip) {
    const verdict = verifyRequest({
      rawBody,
      signature: req.headers[SIGNATURE_HEADER],
      secret: config.ingest.sharedSecret,
      replayWindowMs: config.ingest.replayWindowMs,
      now: clock(),
    });
    if (!verdict.ok) {
      stats.authFailures += 1;
      log.warn('验签失败', { ip, status: verdict.status, reason: verdict.message });
      sendJson(res, verdict.status, { ok: false, message: verdict.message });
      return null;
    }
    return verdict.payload;
  }

  /** 客户端自报版本/服务器（只用于日志，不作为可信数据） */
  function describeClient(payload) {
    const version = typeof payload?.client?.version === 'string' ? payload.client.version.slice(0, 32) : '?';
    const server = typeof payload?.client?.server === 'string' ? payload.client.server.slice(0, 255) : '?';
    return { version, server };
  }

  async function handleShops(req, res, rawBody, ip) {
    const payload = authenticate(req, res, rawBody, ip);
    if (!payload) return;

    const shopList = Array.isArray(payload.shops) ? payload.shops : [];
    const rawKeys = Array.isArray(payload.heartbeatKeys) ? payload.heartbeatKeys : [];

    if (shopList.length > config.ingest.maxShops) {
      sendJson(res, 400, {
        ok: false,
        message: `单次上报商店数超过上限（${shopList.length} > ${config.ingest.maxShops}）`,
      });
      return;
    }
    if (rawKeys.length > config.ingest.maxHeartbeatKeys) {
      sendJson(res, 400, {
        ok: false,
        message: `单次上报心跳键数超过上限（${rawKeys.length} > ${config.ingest.maxHeartbeatKeys}）`,
      });
      return;
    }

    /** @type {object[]} */
    const valid = [];
    let rejected = 0;
    let firstReason = null;
    for (const row of shopList) {
      const verdict = validateShop(row);
      if (verdict.ok) {
        valid.push(row);
      } else {
        rejected += 1;
        if (!firstReason) firstReason = verdict.reason;
      }
    }

    const keys = normalizeHeartbeatKeys(rawKeys);
    const at = localNow(new Date(clock()));
    const client = describeClient(payload);

    let saved = 0;
    let heartbeated = 0;
    try {
      if (valid.length) saved = await writer.upsertRows(valid);
      if (keys.length) heartbeated = await writer.touchLastSeen(keys, at);
    } catch (err) {
      log.error('写库失败', { ip, client: client.version, shops: valid.length, error: err?.message ?? String(err) });
      sendJson(res, 500, { ok: false, message: '服务端写入失败，请稍后重试' });
      return;
    }

    stats.rejectedRows += rejected;
    stats.savedRows += saved;
    stats.heartbeatedRows += heartbeated;

    log.info('ingest', {
      ip,
      client: client.version,
      server: client.server,
      shops: shopList.length,
      saved,
      heartbeated,
      rejected,
      ...(firstReason ? { firstRejectReason: firstReason } : {}),
    });

    sendJson(res, 200, { ok: true, saved, heartbeated, rejected, time: at });
  }

  async function handleHealth(req, res, rawBody, ip) {
    const payload = authenticate(req, res, rawBody, ip);
    if (!payload) return;

    let database = { ok: true };
    if (typeof writer.ping === 'function') {
      try {
        await writer.ping();
      } catch (err) {
        database = { ok: false, message: err?.message ?? String(err) };
      }
    }

    sendJson(res, 200, {
      ok: true,
      service: SERVICE_NAME,
      version: SERVICE_VERSION,
      time: localNow(new Date(clock())),
      database,
    });
  }

  async function handle(req, res) {
    stats.requests += 1;
    let pathname = '/';
    try {
      pathname = new URL(req.url ?? '/', 'http://placeholder').pathname;
    } catch {
      sendJson(res, 400, { ok: false, message: '非法请求路径' });
      return;
    }

    // 运维探活：不需要密钥，也不碰数据库（systemd / nginx / 监控轮询用）
    if (pathname === LIVENESS_PATH) {
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        sendJson(res, 405, { ok: false, message: '只允许 GET' }, { Allow: 'GET, HEAD' });
        return;
      }
      sendJson(res, 200, { ok: true, service: SERVICE_NAME, version: SERVICE_VERSION });
      return;
    }

    if (pathname !== SHOPS_PATH && pathname !== HEALTH_PATH) {
      sendJson(res, 404, { ok: false, message: '未知端点' });
      return;
    }

    if (!config.ingest.enabled) {
      sendJson(res, 404, { ok: false, message: '上报接口未启用' });
      return;
    }

    const ip = clientIp(req, config.http.trustProxy);
    const rate = limiter.hit(ip);
    if (!rate.allowed) {
      stats.rateLimited += 1;
      log.warn('限流', { ip, limit: rate.limit });
      sendJson(
        res,
        429,
        { ok: false, message: '上报过于频繁，请稍后重试' },
        { 'Retry-After': String(rate.retryAfterSec) },
      );
      return;
    }

    if (!ipAllowed(ip, config.ingest.allowedIps)) {
      stats.authFailures += 1;
      log.warn('来源 IP 不在白名单', { ip });
      sendJson(res, 403, { ok: false, message: `来源 IP 不在白名单内：${ip}` });
      return;
    }

    if (req.method !== 'POST') {
      sendJson(res, 405, { ok: false, message: '只允许 POST' }, { Allow: 'POST' });
      return;
    }

    let rawBody;
    try {
      rawBody = await readBody(req, config.ingest.maxBodyBytes);
    } catch (err) {
      if (err?.code === 'TOO_LARGE') {
        stats.authFailures += 1;
        sendJson(
          res,
          413,
          { ok: false, message: `请求体超过上限（${config.ingest.maxBodyBytes} 字节）` },
          { Connection: 'close' },
        );
        return;
      }
      log.debug('读取请求体失败', { ip, error: err?.message ?? String(err) });
      if (!res.writableEnded) {
        sendJson(res, 400, { ok: false, message: '读取请求体失败' });
      }
      return;
    }

    if (pathname === SHOPS_PATH) await handleShops(req, res, rawBody, ip);
    else await handleHealth(req, res, rawBody, ip);
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      log.error('未捕获异常', { url: req.url, error: err?.stack ?? String(err) });
      if (!res.writableEnded) sendJson(res, 500, { ok: false, message: '服务端内部错误' });
    });
  });

  // 超时收紧：本服务只处理短请求，长连接空转没有意义（也降低被拖死的面积）
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  server.keepAliveTimeout = 5000;

  return {
    server,
    stats,
    limiter,
    close: () =>
      new Promise((resolve) => {
        server.close(() => resolve());
        // close() 只等已有连接结束；本服务请求都很短，这里给 3 秒兜底
        setTimeout(resolve, 3000).unref?.();
      }),
  };
}
