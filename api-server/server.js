#!/usr/bin/env node
/**
 * shopsignscanner 上报后端（shopsign ingest API）
 *
 * mod 采集端不再直连 MySQL，而是 HTTPS POST 到这里，由本服务校验后写 `shop_signs`。
 * 客户端只持有一个 HMAC 共享密钥，**没有任何数据库凭据**。
 *
 * 用法：
 *   node server.js              正常启动（读 .env 与环境变量）
 *   node server.js --check      只校验配置，不启动（部署前自检）
 *   DB_DRIVER=fake node server.js  不连库，只打日志（联调 nginx / 密钥用）
 *
 * 部署方式见 ./README.md；接口契约见 ./CONTRACT.md。
 */

import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadConfig } from './src/config.js';
import { loadDotEnvFile } from './src/dotenv.js';
import { createLogger } from './src/log.js';
import { createIngestServer } from './src/http/server.js';
import { createFakeWriter } from './src/writers/fakeWriter.js';
import { createMysqlWriter } from './src/writers/mysqlWriter.js';
import { SERVICE_NAME, SERVICE_VERSION } from './src/version.js';

const HERE = dirname(fileURLToPath(import.meta.url));

/** 依次尝试 api-server/.env 与当前目录 .env（后者不覆盖前者） */
function loadEnvFiles() {
  for (const file of [join(HERE, '.env'), join(process.cwd(), '.env')]) {
    if (!existsSync(file)) continue;
    try {
      loadDotEnvFile(file);
    } catch (err) {
      process.stderr.write(`读取 ${file} 失败：${err.message}\n`);
    }
  }
}

async function main() {
  loadEnvFiles();

  const checkOnly = process.argv.includes('--check');
  const config = loadConfig(process.env);
  const log = createLogger({ level: config.log.level });

  for (const warning of config.warnings) log.warn('配置提醒', { message: warning });

  if (!config.ok) {
    for (const problem of config.problems) log.error('配置错误', { message: problem });
    process.exitCode = 1;
    return;
  }

  if (checkOnly) {
    log.info('配置校验通过', {
      listen: `${config.http.host}:${config.http.port}`,
      database: `${config.db.host}:${config.db.port}/${config.db.database}.${config.db.table}`,
      rateLimit: `${config.ingest.rateLimit}/${config.ingest.rateWindowMs}ms`,
      allowedIps: config.ingest.allowedIps.length ? config.ingest.allowedIps.join(',') : '不限',
    });
    return;
  }

  const driver = String(process.env.DB_DRIVER ?? 'mysql').toLowerCase();
  let writer;
  if (driver === 'fake') {
    writer = createFakeWriter();
    log.warn('使用假写入器（不落库）', { driver });
  } else {
    try {
      writer = await createMysqlWriter(config.db, { log });
    } catch (err) {
      log.error('创建数据库写入器失败', { message: err?.message ?? String(err) });
      process.exitCode = 1;
      return;
    }
  }

  // 启动时探一次库：失败不退出（数据库可能正在重启），但要让运维立刻看见
  if (typeof writer.ping === 'function') {
    try {
      await writer.ping();
      log.info('数据库连接成功', {
        database: `${config.db.host}:${config.db.port}/${config.db.database}.${config.db.table}`,
      });
    } catch (err) {
      log.warn('数据库暂不可用，服务继续启动（收到请求时会重试）', {
        message: err?.message ?? String(err),
      });
    }
  }

  const { server, close, stats } = createIngestServer({ config, writer, log });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.http.port, config.http.host, resolve);
  }).catch((err) => {
    log.error('监听失败', { host: config.http.host, port: config.http.port, message: err?.message ?? String(err) });
    throw err;
  });

  log.info('服务已启动', {
    service: SERVICE_NAME,
    version: SERVICE_VERSION,
    listen: `${config.http.host}:${config.http.port}`,
    trustProxy: config.http.trustProxy,
    allowedIps: config.ingest.allowedIps.length ? config.ingest.allowedIps.join(',') : '不限',
  });

  let shuttingDown = false;
  const shutdown = async (signal) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info('收到退出信号，开始收尾', {
      signal,
      requests: stats.requests,
      saved: stats.savedRows,
      heartbeated: stats.heartbeatedRows,
    });
    // 兜底：无论如何 10 秒内退出，别让 systemd 一直等
    setTimeout(() => process.exit(0), 10000).unref();
    try {
      await close();
      await writer.close?.();
    } catch (err) {
      log.error('收尾时出错', { message: err?.message ?? String(err) });
    }
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('unhandledRejection', (reason) => {
    log.error('未处理的 Promise 拒绝', { message: reason instanceof Error ? reason.stack : String(reason) });
  });
  process.on('uncaughtException', (err) => {
    log.error('未捕获异常', { message: err?.stack ?? String(err) });
  });
}

main().catch((err) => {
  process.stderr.write(`启动失败：${err?.stack ?? err}\n`);
  process.exit(1);
});
