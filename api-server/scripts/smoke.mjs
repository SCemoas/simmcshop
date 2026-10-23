#!/usr/bin/env node
/**
 * 上线自检（在部署机或本地对着域名跑都行）
 *
 *   node scripts/smoke.mjs --url https://shop.example.com --secret <共享密钥>
 *
 * 只发三种请求，**不写任何商店数据**：
 *   1. GET  /healthz              —— 反代与进程是否通
 *   2. POST /api/ingest/health    —— 签名/密钥/时区窗口是否对，顺便看数据库
 *   3. POST /api/ingest/health 带错签名 —— 必须回 401（防止「谁都能写库」）
 *
 * 退出码 0 = 全部符合预期；非 0 = 有不符合的项（输出里逐条写明）。
 */

import crypto from 'node:crypto';

import { SERVICE_NAME } from '../src/version.js';

function parseArgs(argv) {
  const args = { url: '', secret: '', timeout: 10_000 };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i];
    const value = argv[i + 1];
    if (flag === '--help' || flag === '-h') {
      args.help = true;
    } else if (flag === '--url') {
      args.url = value ?? '';
      i += 1;
    } else if (flag === '--secret') {
      args.secret = value ?? '';
      i += 1;
    } else if (flag === '--timeout') {
      args.timeout = Number(value) || args.timeout;
      i += 1;
    } else {
      throw new Error(`未知参数：${flag}`);
    }
  }
  return args;
}

const USAGE = `用法：node scripts/smoke.mjs --url <后端根地址> [--secret <共享密钥>]

  --url      例如 https://shop.example.com 或 http://127.0.0.1:3200
  --secret   与 INGEST_SHARED_SECRET 一致；不给则跳过验签相关项目
  --timeout  单请求超时毫秒（默认 10000）`;

function sign(rawBody, secret) {
  return crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
}

/** 结果收集：最后统一打印 + 决定退出码 */
const results = [];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✔' : '✖'} ${name}${detail ? ` —— ${detail}` : ''}`);
}

/** 发一次带签名的 health 请求，返回 {status, body} */
async function postHealth(base, rawBody, signature, timeout) {
  const res = await fetch(`${base}/api/ingest/health`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'X-ShopSign-Signature': signature,
    },
    body: rawBody,
    signal: timeout,
  });
  const body = await res.json().catch(() => null);
  return { status: res.status, body };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.url) {
    console.log(USAGE);
    process.exitCode = args.help ? 0 : 2;
    return;
  }

  const base = args.url.replace(/\/+$/, '');
  const timeout = () => AbortSignal.timeout(args.timeout);

  // 1. 免签名探活：反代有没有把 /healthz 转到本服务
  try {
    const res = await fetch(`${base}/healthz`, { signal: timeout() });
    const body = await res.json().catch(() => null);
    record(
      'GET /healthz',
      res.status === 200 && body?.ok === true,
      `status=${res.status} service=${body?.service} version=${body?.version}`,
    );
  } catch (error) {
    record('GET /healthz', false, `请求失败：${error.message}`);
  }

  if (!args.secret) {
    console.log('ℹ 未给 --secret，跳过验签相关项目');
  } else {
    // 2. 正确的签名：密钥、时间窗口、数据库是否都通
    const payload = JSON.stringify({
      timestamp: Date.now(),
      client: { version: 'smoke', server: 'smoke' },
      shops: [],
      heartbeatKeys: [],
    });
    const rawBody = Buffer.from(payload, 'utf8');
    try {
      const { status, body } = await postHealth(base, rawBody, sign(rawBody, args.secret), timeout());
      const healthy = status === 200 && body?.ok === true;
      record('POST /api/ingest/health（签名正确）', healthy, `status=${status} database=${JSON.stringify(body?.database)}`);
      if (healthy) {
        record('数据库连通', body?.database?.ok === true, body?.database?.ok === true ? '' : String(body?.database?.message ?? 'database.ok=false'));
      }
    } catch (error) {
      record('POST /api/ingest/health（签名正确）', false, `请求失败：${error.message}`);
    }

    // 3. 错误签名必须被拒绝
    const badBody = Buffer.from(JSON.stringify({ timestamp: Date.now() }), 'utf8');
    try {
      const { status } = await postHealth(base, badBody, sign(badBody, `${args.secret}-wrong`), timeout());
      record('POST /api/ingest/health（签名错误）', status === 401, `期望 401，实际 ${status}`);
    } catch (error) {
      record('POST /api/ingest/health（签名错误）', false, `请求失败：${error.message}`);
    }
  }

  const failed = results.filter((item) => !item.ok);
  console.log(`\n服务名常量：${SERVICE_NAME}`);
  console.log(failed.length === 0 ? `全部 ${results.length} 项通过` : `${failed.length}/${results.length} 项不符合预期`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

main().catch((error) => {
  console.error(`自检脚本异常：${error.stack ?? error.message}`);
  process.exitCode = 1;
});
