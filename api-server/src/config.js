/**
 * 配置：全部来自环境变量（或同目录 .env），**代码里不写任何凭据**
 *
 * 与站点时代的最大区别：本服务独属于「采集端 → 数据库」这一段，
 * 数据库口令只存在服务器上，客户端 mod 只有 HMAC 共享密钥。
 *
 * 变量一览（详见 api-server/README.md 与 .env.example）：
 *   HTTP     PORT / HOST / TRUST_PROXY / LOG_LEVEL
 *   鉴权     INGEST_ENABLED / INGEST_SHARED_SECRET / INGEST_ALLOWED_IPS
 *            INGEST_REPLAY_WINDOW_MS
 *   限流     INGEST_RATE_LIMIT / INGEST_RATE_WINDOW_MS
 *   上限     INGEST_MAX_SHOPS / INGEST_MAX_HEARTBEAT_KEYS / INGEST_MAX_BODY_BYTES
 *   数据库   DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME / DB_TABLE / DB_POOL
 */

/** 单次上报上限的默认值（与 CONTRACT.md 第 3 节一致） */
export const LIMIT_DEFAULTS = {
  maxShops: 200,
  maxHeartbeatKeys: 5000,
  maxBodyBytes: 1024 * 1024,
};

/**
 * 解析整数，非法或越界时回落到默认值并记录问题
 * @param {Record<string,string|undefined>} env
 * @param {string} name
 * @param {number} fallback
 * @param {{min?: number, max?: number, problems: string[]}} opts
 */
function intEnv(env, name, fallback, opts = {}) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || !Number.isInteger(value)) {
    opts.problems.push(`${name} 不是整数（${raw}），已用默认值 ${fallback}`);
    return fallback;
  }
  if (opts.min !== undefined && value < opts.min) {
    opts.problems.push(`${name} 小于下限 ${opts.min}（${value}），已用默认值 ${fallback}`);
    return fallback;
  }
  if (opts.max !== undefined && value > opts.max) {
    opts.problems.push(`${name} 大于上限 ${opts.max}（${value}），已用默认值 ${fallback}`);
    return fallback;
  }
  return value;
}

/** 布尔：除 0/false/no/off 之外都算 true */
function boolEnv(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  return !['0', 'false', 'no', 'off'].includes(String(raw).trim().toLowerCase());
}

/** 逗号分隔字符串 → 去空数组 */
function listEnv(env, name) {
  return String(env[name] ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

/**
 * 载入配置
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {{
 *   ok: boolean, problems: string[], warnings: string[],
 *   http: {port:number, host:string, trustProxy:boolean},
 *   log: {level:string},
 *   ingest: {enabled:boolean, sharedSecret:string, allowedIps:string[],
 *            replayWindowMs:number, maxShops:number, maxHeartbeatKeys:number,
 *            maxBodyBytes:number, rateLimit:number, rateWindowMs:number},
 *   db: {host:string, port:number, user:string, password:string, database:string,
 *        table:string, poolSize:number}
 * }}
 */
export function loadConfig(env = process.env) {
  const problems = [];
  const warnings = [];

  // ---- 数据库表名会拼进 SQL，必须白名单化（不接受奇怪字符） ----
  const table = String(env.DB_TABLE ?? 'shop_signs').trim();
  const safeTable = /^[A-Za-z0-9_]{1,64}$/.test(table) ? table : 'shop_signs';
  if (safeTable !== table) problems.push(`DB_TABLE 只能由字母数字下划线组成（收到 "${table}"）`);

  const config = {
    ok: true,
    problems,
    warnings,
    http: {
      port: intEnv(env, 'PORT', 3200, { min: 1, max: 65535, problems }),
      host: String(env.HOST ?? '127.0.0.1').trim() || '127.0.0.1',
      /**
       * 是否信任 X-Forwarded-For
       *
       * 默认只在监听回环地址时开启：服务挂在 nginx 后面，客户端只会是 127.0.0.1，
       * 真实 IP 由 nginx 写进 X-Forwarded-For。若直接对外监听（HOST=0.0.0.0），
       * 必须显式设 TRUST_PROXY=false，否则客户端可以伪造来源 IP 绕过 IP 白名单。
       */
      trustProxy: boolEnv(env, 'TRUST_PROXY', String(env.HOST ?? '127.0.0.1').trim() === '127.0.0.1'),
    },
    log: { level: String(env.LOG_LEVEL ?? 'info').trim() || 'info' },
    ingest: {
      enabled: boolEnv(env, 'INGEST_ENABLED', true),
      sharedSecret: String(env.INGEST_SHARED_SECRET ?? '').trim(),
      /** 空数组 = 不限制来源 IP（仍受 HMAC 保护） */
      allowedIps: listEnv(env, 'INGEST_ALLOWED_IPS'),
      replayWindowMs: intEnv(env, 'INGEST_REPLAY_WINDOW_MS', 5 * 60 * 1000, {
        min: 1000,
        max: 60 * 60 * 1000,
        problems,
      }),
      maxShops: intEnv(env, 'INGEST_MAX_SHOPS', LIMIT_DEFAULTS.maxShops, {
        min: 1,
        max: 5000,
        problems,
      }),
      maxHeartbeatKeys: intEnv(env, 'INGEST_MAX_HEARTBEAT_KEYS', LIMIT_DEFAULTS.maxHeartbeatKeys, {
        min: 1,
        max: 100000,
        problems,
      }),
      maxBodyBytes: intEnv(env, 'INGEST_MAX_BODY_BYTES', LIMIT_DEFAULTS.maxBodyBytes, {
        min: 1024,
        max: 64 * 1024 * 1024,
        problems,
      }),
      rateLimit: intEnv(env, 'INGEST_RATE_LIMIT', 600, { min: 1, max: 1_000_000, problems }),
      rateWindowMs: intEnv(env, 'INGEST_RATE_WINDOW_MS', 60 * 1000, {
        min: 1000,
        max: 60 * 60 * 1000,
        problems,
      }),
    },
    db: {
      host: String(env.DB_HOST ?? '127.0.0.1').trim() || '127.0.0.1',
      port: intEnv(env, 'DB_PORT', 3306, { min: 1, max: 65535, problems }),
      user: String(env.DB_USER ?? '').trim(),
      password: String(env.DB_PASSWORD ?? ''),
      database: String(env.DB_NAME ?? 'shop').trim() || 'shop',
      table: safeTable,
      poolSize: intEnv(env, 'DB_POOL', 4, { min: 1, max: 32, problems }),
    },
  };

  // ---- 启动前的一致性校验（宁可起不来，也不要带病运行） ----
  if (config.ingest.enabled && !config.ingest.sharedSecret) {
    problems.push('INGEST_SHARED_SECRET 未设置：上报接口启用但没有密钥，任何请求都无法验签');
  }
  if (config.ingest.sharedSecret && config.ingest.sharedSecret.length < 16) {
    warnings.push('INGEST_SHARED_SECRET 少于 16 个字符，建议至少 32 位随机串');
  }
  if (!config.db.user) {
    problems.push('DB_USER 未设置');
  }
  if (!config.db.password) {
    warnings.push('DB_PASSWORD 为空：只有本地无口令账号才可能连通');
  }
  if (!config.http.trustProxy && !config.ingest.allowedIps.length) {
    warnings.push('直接对外监听且未设 INGEST_ALLOWED_IPS：建议至少配 IP 白名单或前置反向代理');
  }

  config.ok = problems.length === 0;
  return config;
}
