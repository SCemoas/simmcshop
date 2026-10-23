/**
 * 极简日志
 *
 * 本服务是常驻后台的守护进程，日志主要给 journald / pm2 / docker logs 看，
 * 因此只用一行一条的纯文本，带 ISO 时间戳与等级，不引第三方库。
 * 结构化字段以 `k=v` 追加，方便 grep 与后续接日志系统。
 */

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40, silent: 100 };

/**
 * 创建 logger
 * @param {{level?: string, sink?: (line: string) => void}} [options]
 *        sink 缺省写 stdout/stderr，测试里可注入收集函数
 */
export function createLogger(options = {}) {
  const threshold = LEVELS[String(options.level ?? 'info').toLowerCase()] ?? LEVELS.info;
  const sink = options.sink ?? null;

  function emit(level, message, fields) {
    if (LEVELS[level] < threshold) return;
    const parts = [`${new Date().toISOString()}`, level.toUpperCase().padEnd(5), message];
    if (fields && typeof fields === 'object') {
      for (const [k, v] of Object.entries(fields)) {
        if (v === undefined) continue;
        const text = typeof v === 'string' ? v : JSON.stringify(v);
        // 值里的空白与引号用 JSON 包一层，避免多行日志把 k=v 拆开
        parts.push(`${k}=${/[\s"\\]/.test(text) ? JSON.stringify(text) : text}`);
      }
    }
    const line = parts.join(' ');
    if (sink) {
      sink(line);
      return;
    }
    if (level === 'error' || level === 'warn') process.stderr.write(`${line}\n`);
    else process.stdout.write(`${line}\n`);
  }

  return {
    level: options.level ?? 'info',
    debug: (m, f) => emit('debug', m, f),
    info: (m, f) => emit('info', m, f),
    warn: (m, f) => emit('warn', m, f),
    error: (m, f) => emit('error', m, f),
  };
}
