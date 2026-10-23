/**
 * 极简 .env 读取（不引 dotenv）
 *
 * 只支持部署时会用到的子集：
 *   - 空行与 `#` 开头的注释行
 *   - `KEY=VALUE`，等号两侧的空白会被去掉
 *   - 值两侧成对的单/双引号会被去掉（双引号内的 `\n` 展开成换行）
 *   - `export KEY=VALUE` 前缀（从 shell 里复制过来时常见）
 *
 * **已存在的环境变量优先**：.env 只补齐缺失项，
 * 这样 systemd `Environment=` / docker `-e` 永远能覆盖文件。
 */

import { readFileSync } from 'node:fs';

/**
 * 解析 .env 文本
 * @param {string} text
 * @returns {Record<string, string>}
 */
export function parseDotEnv(text) {
  const out = {};
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const body = line.startsWith('export ') ? line.slice(7).trim() : line;
    const eq = body.indexOf('=');
    if (eq <= 0) continue;
    const key = body.slice(0, eq).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue;
    let value = body.slice(eq + 1).trim();
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1).replace(/\\n/g, '\n').replace(/\\r/g, '\r');
    } else if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

/**
 * 读取 .env 文件并补齐到目标对象（默认 process.env）
 * @param {string} path
 * @param {Record<string, string|undefined>} [target]
 * @returns {number} 实际写入的项数
 */
export function loadDotEnvFile(path, target = process.env) {
  const values = parseDotEnv(readFileSync(path, 'utf8'));
  let applied = 0;
  for (const [key, value] of Object.entries(values)) {
    if (target[key] !== undefined && target[key] !== '') continue;
    target[key] = value;
    applied += 1;
  }
  return applied;
}
