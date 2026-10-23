/**
 * 时间工具
 *
 * 契约（api-server/CONTRACT.md 第 3 节）要求上报的时间串是
 * `YYYY-MM-DD HH:MM:SS` **本地时区**（不是 UTC），与 mod 端
 * `DatabaseReporter` 写入 MySQL DATETIME 时的口径一致 ——
 * MySQL 的 DATETIME 没有时区信息，存的是「墙上的钟」。
 *
 * 因此这里刻意**不用** `toISOString()`（那是 UTC），
 * 而是按进程本地时区手工拼。
 */

/** 两位补零 */
function pad2(n) {
  return n < 10 ? `0${n}` : String(n);
}

/**
 * 把 Date 格式化为本地 `YYYY-MM-DD HH:MM:SS`
 * @param {Date} date
 * @returns {string}
 */
export function formatLocalDateTime(date) {
  return (
    `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ` +
    `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
  );
}

/**
 * 当前本地时间串
 * @param {Date} [date] 便于测试注入
 * @returns {string}
 */
export function now(date = new Date()) {
  return formatLocalDateTime(date);
}
