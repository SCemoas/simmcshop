/**
 * 版本与打包信息自检
 *
 * 目的：`src/version.js` 里写死的版本号、package.json、以及 systemd/镜像 tag
 * 三者不一致时，运维看 `/healthz` 会以为部署没生效。这里把前两者钉在一起。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { SERVICE_NAME, SERVICE_VERSION } from '../src/version.js';

const pkg = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'package.json'), 'utf8'),
);

test('version.js 与 package.json 版本一致', () => {
  assert.equal(SERVICE_VERSION, pkg.version);
  assert.match(SERVICE_VERSION, /^\d+\.\d+\.\d+$/);
});

test('服务名与 npm 包名一致（日志/探活里能直接对上部署目录）', () => {
  assert.equal(SERVICE_NAME, 'shopsign-ingest-api');
  assert.equal(pkg.name, SERVICE_NAME);
});

test('package.json：ESM、声明 Node 版本、只依赖 mysql2、有 start/test 脚本', () => {
  assert.equal(pkg.type, 'module');
  assert.ok(pkg.engines?.node, '必须声明 engines.node');
  assert.deepEqual(Object.keys(pkg.dependencies ?? {}), ['mysql2'], '除 mysql2 外不应再有运行时依赖');
  assert.ok(pkg.scripts?.start);
  assert.ok(pkg.scripts?.test);
  assert.equal(pkg.private, true, '这不是要发布到 npm 的包');
});
