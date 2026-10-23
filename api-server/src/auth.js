/**
 * 鉴权：HMAC 验签 + 时间戳防重放 + 来源 IP 白名单 + 按 IP 限流
 *
 * 与站点时代 `/api/plugin`、`/api/ingest` 完全同一套做法，因此 mod 端
 * `ApiClient` 一个字都不用改：
 *   X-ShopSign-Signature = HMAC_SHA256(key = INGEST_SHARED_SECRET,
 *                                    msg = 原始请求体字节) 的小写十六进制
 *
 * 为什么必须基于**原始字节**验签：JSON 重新序列化后键序/空白/数字格式都可能变，
 * 用 `JSON.stringify(payload)` 重算会时对时错，还给了攻击者重排键值的机会。
 */

import crypto from 'node:crypto';

/** 签名头名（与 `ApiClient.SIGNATURE_HEADER` 一致） */
export const SIGNATURE_HEADER = 'x-shopsign-signature';

/**
 * 计算请求体签名
 * @param {Buffer|string} rawBody 原始请求体
 * @param {string} sharedSecret
 * @returns {string} 小写十六进制
 */
export function signBody(rawBody, sharedSecret) {
  return crypto.createHmac('sha256', String(sharedSecret)).update(rawBody).digest('hex');
}

/**
 * 定长比较（防时序侧信道）
 *
 * 长度不同或为空一律 false：空签名头不该被当成「长度 0 的合法值」。
 * @param {string} expected
 * @param {string} actual
 * @returns {boolean}
 */
export function safeEqualHex(expected, actual) {
  const a = Buffer.from(String(expected ?? ''), 'utf8');
  const b = Buffer.from(String(actual ?? ''), 'utf8');
  if (a.length === 0 || a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

/**
 * 取客户端 IP
 *
 * 走后置反代时真实 IP 在 `X-Forwarded-For`（第一段是原始客户端）。
 * 只有 `trustProxy` 为真才采信这个头 —— 直接对外监听时它能被随意伪造。
 *
 * @param {{headers: Record<string, string|string[]|undefined>, socket?: {remoteAddress?: string}}} req
 * @param {boolean} trustProxy
 * @returns {string}
 */
export function clientIp(req, trustProxy) {
  const raw = trustProxy ? req.headers?.['x-forwarded-for'] : undefined;
  const first = Array.isArray(raw) ? raw[0] : raw;
  const candidate = first ? String(first).split(',')[0].trim() : '';
  const ip = candidate || String(req.socket?.remoteAddress ?? '');
  return ip.replace(/^::ffff:/, '').replace(/^\[|\]$/g, '');
}

/**
 * IP 白名单
 * @param {string} ip
 * @param {string[]} allowed
 * @returns {boolean} 白名单为空表示不限制
 */
export function ipAllowed(ip, allowed) {
  if (!allowed || !allowed.length) return true;
  return allowed.includes(String(ip ?? ''));
}

/**
 * 固定窗口限流（每 IP 一个计数器）
 *
 * 为什么不用漏桶/令牌桶：上报是**突发**的（一次扫描几十条，可 mod 端已批量化），
 * 固定窗口足够拦住「被打爆」这一种真实风险，实现也能被测试钉死。
 *
 * @param {{limit: number, windowMs: number, now?: () => number, maxEntries?: number}} options
 */
export function createRateLimiter(options) {
  const limit = Math.max(1, Number(options.limit) || 1);
  const windowMs = Math.max(1, Number(options.windowMs) || 60000);
  const nowFn = options.now ?? Date.now;
  /** 存活上限：防止被海量随机 IP 撑爆内存 */
  const maxEntries = options.maxEntries ?? 20000;
  /** @type {Map<string, {count: number, resetAt: number}>} */
  const buckets = new Map();

  function prune(currentTime) {
    for (const [key, bucket] of buckets) {
      if (bucket.resetAt <= currentTime) buckets.delete(key);
    }
  }

  return {
    /**
     * 记一次访问
     * @param {string} key 通常是 IP
     * @returns {{allowed: boolean, remaining: number, retryAfterSec: number, limit: number}}
     */
    hit(key) {
      const time = nowFn();
      let bucket = buckets.get(String(key));
      if (!bucket || bucket.resetAt <= time) {
        bucket = { count: 0, resetAt: time + windowMs };
        buckets.set(String(key), bucket);
        // 定期清理过期桶：只在超阈值时做，避免每次请求都遍历
        if (buckets.size > maxEntries) prune(time);
      }
      bucket.count += 1;
      const allowed = bucket.count <= limit;
      return {
        allowed,
        remaining: Math.max(0, limit - bucket.count),
        retryAfterSec: Math.max(1, Math.ceil((bucket.resetAt - time) / 1000)),
        limit,
      };
    },
    get size() {
      return buckets.size;
    },
    reset() {
      buckets.clear();
    },
  };
}

/**
 * 验签 + 解析 + 时间戳校验
 *
 * 顺序刻意与站点时代一致：先验签（便宜、挡住绝大多数垃圾请求），
 * 再解析 JSON，最后看时间戳。
 *
 * @param {{rawBody: Buffer|undefined, signature: string|undefined, secret: string,
 *          replayWindowMs: number, now?: number}} input
 * @returns {{ok: true, payload: any} | {ok: false, status: number, message: string}}
 */
export function verifyRequest(input) {
  const { rawBody, signature, secret, replayWindowMs } = input;
  const time = input.now ?? Date.now();

  if (!secret) {
    // 服务端配置问题，不是客户端错误：显式 500，别让客户端以为密钥填错了
    return { ok: false, status: 500, message: '服务端未配置 INGEST_SHARED_SECRET，上报接口不可用' };
  }
  if (!signature) {
    return { ok: false, status: 401, message: '缺少 X-ShopSign-Signature 签名头' };
  }
  if (!rawBody || !rawBody.length) {
    return { ok: false, status: 400, message: '请求体为空' };
  }
  if (!safeEqualHex(signBody(rawBody, secret), String(signature).trim())) {
    return { ok: false, status: 401, message: '签名校验失败（检查 INGEST_SHARED_SECRET 与客户端 apiSecret 是否一致）' };
  }

  let payload;
  try {
    payload = JSON.parse(rawBody.toString('utf8'));
  } catch {
    return { ok: false, status: 400, message: '请求体不是合法 JSON' };
  }

  const ts = Number(payload?.timestamp);
  if (!Number.isFinite(ts)) {
    return { ok: false, status: 400, message: '缺少 timestamp 字段（毫秒）' };
  }
  const skew = Math.abs(time - ts);
  if (skew > replayWindowMs) {
    return {
      ok: false,
      status: 401,
      message: `时间戳超出允许范围（偏差 ${Math.round(skew / 1000)} 秒），请检查客户端时钟`,
    };
  }

  return { ok: true, payload };
}
