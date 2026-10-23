/**
 * 位置键（location_key）—— 必须与 Java 与 MySQL 两处完全一致
 *
 * 三种形式（见 CONTRACT.md 第 5 节）：
 *   Java   `ShopRecord.locationKeyMd5()`  → MD5(server|world|x,y,z|side) 小写 hex
 *   MySQL  `MD5(CONCAT(server,'|',world,'|',x,',',y,',',z,'|',side))`
 *   本服务 下面这个函数
 *
 * 一致性靠什么保证：
 *   - 分隔符与顺序逐字相同（`|` 与 `,`，没有空格）
 *   - 数字用十进制整数拼（Java 的 int 与 JS 的 Number 在这几个坐标上一致）
 *   - MD5 一律小写十六进制（Java 与小写化的 MySQL 输出、Node `digest('hex')` 都小写）
 *   - 编码 UTF-8（Java 侧 `getBytes(UTF_8)`；MySQL 库是 utf8mb4，
 *     MD5() 作用在 utf8 字节上，对 BMP 内的汉字同样一致）
 *
 * 这项不一致的后果是全表重复（每个商店都被当成新商店），
 * 所以 `test/locationKey.test.js` 里钉了几组与 Java/SQL 相互校验的向量。
 */

import crypto from 'node:crypto';

/**
 * 计算位置键
 * @param {{server: string, world: string, x: number, y: number, z: number, side: string}} r
 * @returns {string} 32 位小写十六进制
 */
export function locationKey(r) {
  const text = `${r.server}|${r.world}|${r.x},${r.y},${r.z}|${r.side}`;
  return crypto.createHash('md5').update(text, 'utf8').digest('hex');
}

/**
 * 位置键的「源码文本」（只给测试与日志用：出现不一致时能直接看到拼了什么）
 * @param {{server: string, world: string, x: number, y: number, z: number, side: string}} r
 * @returns {string}
 */
export function locationKeySource(r) {
  return `${r.server}|${r.world}|${r.x},${r.y},${r.z}|${r.side}`;
}

/** 是否是合法位置键（32 位 hex，大小写都收，下游会小写化） */
export function isLocationKey(value) {
  return typeof value === 'string' && /^[0-9a-f]{32}$/i.test(value);
}
