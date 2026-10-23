/**
 * 配置解析
 *
 * 目标：配错就当启动失败（而不是带着半套配置跑起来，把 401 打给全服玩家），
 * 但配错的值本身要能回落到默认值 + 明确报出问题。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { loadConfig } from '../src/config.js';
import { parseDotEnv } from '../src/dotenv.js';
import { SECRET } from '../src/testing/harness.js';

const MINIMAL = { INGEST_SHARED_SECRET: SECRET, DB_USER: 'shop_ingest', DB_PASSWORD: 'pw' };

test('默认值：回环监听 + 3200 端口 + 表 shop_signs', () => {
  const config = loadConfig({ ...MINIMAL });
  assert.equal(config.ok, true, config.problems.join('；'));
  assert.equal(config.http.host, '127.0.0.1');
  assert.equal(config.http.port, 3200);
  assert.equal(config.http.trustProxy, true, '监听回环时默认信任 X-Forwarded-For');
  assert.equal(config.db.table, 'shop_signs');
  assert.equal(config.db.database, 'shop');
  assert.equal(config.db.port, 3306);
  assert.equal(config.db.poolSize, 4);
  assert.equal(config.ingest.enabled, true);
  assert.equal(config.ingest.maxShops, 200);
  assert.equal(config.ingest.maxHeartbeatKeys, 5000);
  assert.equal(config.ingest.replayWindowMs, 300000);
  assert.equal(config.ingest.rateLimit, 600);
  assert.equal(config.ingest.rateWindowMs, 60000);
  assert.equal(config.ingest.maxBodyBytes, 1024 * 1024);
  assert.deepEqual(config.ingest.allowedIps, []);
  assert.equal(config.log.level, 'info');
});

test('缺 INGEST_SHARED_SECRET 且接口启用 → 启动失败（不接受「谁都能写库」的状态）', () => {
  const config = loadConfig({ DB_USER: 'u', DB_PASSWORD: 'p' });
  assert.equal(config.ok, false);
  assert.match(config.problems.join('\n'), /INGEST_SHARED_SECRET 未设置/);
});

test('关掉接口时不再要求密钥（灰度/应急）', () => {
  const config = loadConfig({ DB_USER: 'u', DB_PASSWORD: 'p', INGEST_ENABLED: 'false' });
  assert.equal(config.ok, true, config.problems.join('；'));
  assert.equal(config.ingest.enabled, false);
});

test('缺 DB_USER → 启动失败；DB_PASSWORD 空只提醒不拦', () => {
  const noUser = loadConfig({ INGEST_SHARED_SECRET: SECRET });
  assert.equal(noUser.ok, false);
  assert.match(noUser.problems.join('\n'), /DB_USER 未设置/);

  const noPassword = loadConfig({ INGEST_SHARED_SECRET: SECRET, DB_USER: 'u' });
  assert.equal(noPassword.ok, true, noPassword.problems.join('；'));
  assert.match(noPassword.warnings.join('\n'), /DB_PASSWORD 为空/);
});

test('表名注入被挡住（回落默认表名并报错）', () => {
  for (const bad of ['shop_signs; DROP TABLE x', 'shop signs', 'shop.signs']) {
    const config = loadConfig({ ...MINIMAL, DB_TABLE: bad });
    assert.equal(config.db.table, 'shop_signs');
    assert.equal(config.ok, false);
    assert.match(config.problems.join('\n'), /DB_TABLE/);
  }
});

test('直接对外监听时 TRUST_PROXY 默认关闭，避免伪造 X-Forwarded-For 绕过白名单', () => {
  const config = loadConfig({ ...MINIMAL, HOST: '0.0.0.0' });
  assert.equal(config.http.trustProxy, false);
  assert.match(config.warnings.join('\n'), /建议至少配 IP 白名单/);

  assert.equal(loadConfig({ ...MINIMAL, HOST: '0.0.0.0', TRUST_PROXY: 'true' }).http.trustProxy, true);
  assert.equal(loadConfig({ ...MINIMAL, TRUST_PROXY: 'false' }).http.trustProxy, false);
});

test('IP 白名单：逗号分隔、去空白、去空项', () => {
  const config = loadConfig({ ...MINIMAL, INGEST_ALLOWED_IPS: ' 1.2.3.4 , 5.6.7.8 ,, ' });
  assert.deepEqual(config.ingest.allowedIps, ['1.2.3.4', '5.6.7.8']);
});

test('非法整数回落默认值并报问题；布尔值容错', () => {
  const config = loadConfig({ ...MINIMAL, PORT: 'abc', INGEST_MAX_SHOPS: '0', INGEST_ENABLED: 'no' });
  assert.equal(config.http.port, 3200);
  assert.equal(config.ingest.maxShops, 200);
  assert.equal(config.ingest.enabled, false);
  assert.equal(config.problems.filter((p) => /PORT|INGEST_MAX_SHOPS/.test(p)).length, 2);
});

test('密钥过短只提醒不拦（避免把已有部署拦死）', () => {
  const config = loadConfig({ ...MINIMAL, INGEST_SHARED_SECRET: 'short' });
  assert.equal(config.ok, true);
  assert.match(config.warnings.join('\n'), /少于 16 个字符/);
});

test('端口边界：0 与 65536 都回落为 3200', () => {
  assert.equal(loadConfig({ ...MINIMAL, PORT: '0' }).http.port, 3200);
  assert.equal(loadConfig({ ...MINIMAL, PORT: '65536' }).http.port, 3200);
  assert.equal(loadConfig({ ...MINIMAL, PORT: '8443' }).http.port, 8443);
});

test('.env 解析：注释、export 前缀、引号、等号在值里', () => {
  const parsed = parseDotEnv(
    [
      '# 注释',
      '',
      ' A=1 ',
      'export B=two words',
      'C="quoted value"',
      "D='single'",
      'E=a=b=c',
      'F="line1\\nline2"',
      'BAD KEY=x',
      '=nokey',
      'G',
    ].join('\r\n'),
  );
  assert.deepEqual(parsed, {
    A: '1',
    B: 'two words',
    C: 'quoted value',
    D: 'single',
    E: 'a=b=c',
    F: 'line1\nline2',
  });
});
