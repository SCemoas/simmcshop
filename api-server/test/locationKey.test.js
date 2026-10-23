/**
 * 位置键一致性
 *
 * 这里的期望值是用**另一套实现**（.NET `System.Security.Cryptography.MD5`，
 * 而不是 Node 的 crypto）对同样的字符串算出来的，因此能真正发现
 * 「拼接格式被改动」这类问题 —— 而用 Node 自己算期望值只是自证。
 *
 * Java 侧对应 `ShopRecord.locationKey()`：
 *   server + "|" + world + "|" + x + "," + y + "," + z + "|" + side
 * MySQL 侧对应 MD5(CONCAT(server,'|',world,'|',x,',',y,',',z,'|',side))。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { isLocationKey, locationKey, locationKeySource } from '../src/locationKey.js';

test('位置键：与 Java / MySQL 三处实现对齐的标准向量', () => {
  assert.equal(locationKeySource({ server: 'play.example.com', world: 'minecraft:overworld', x: 100, y: 64, z: -200, side: 'front' }), 'play.example.com|minecraft:overworld|100,64,-200|front');
  assert.equal(
    locationKey({ server: 'play.example.com', world: 'minecraft:overworld', x: 100, y: 64, z: -200, side: 'front' }),
    '0c2fd533bd93307823296cb0dae86def',
  );
});

test('位置键：负坐标与大整数不改变拼接格式', () => {
  assert.equal(
    locationKey({ server: 'play.example.com', world: 'minecraft:overworld', x: -12, y: -60, z: 4294967295, side: 'back' }),
    'fa1a66e58deb1afecf213bb768a2b562',
  );
});

test('位置键：中文（UTF-8 多字节）服务器/世界名与 Java 的 UTF_8 编码一致', () => {
  assert.equal(
    locationKey({ server: '测试服.example.com', world: '世界', x: 0, y: 0, z: 0, side: 'front' }),
    '32c0652126c91ff65861375085c29e37',
  );
});

test('位置键：32 位小写 hex，任何字段变化都会换键', () => {
  const base = { server: 'a', world: 'w', x: 1, y: 2, z: 3, side: 'front' };
  const key = locationKey(base);
  assert.match(key, /^[0-9a-f]{32}$/);
  for (const variant of [
    { ...base, server: 'b' },
    { ...base, world: 'w2' },
    { ...base, x: 2 },
    { ...base, y: 3 },
    { ...base, z: 4 },
    { ...base, side: 'back' },
  ]) {
    assert.notEqual(locationKey(variant), key, `变体 ${JSON.stringify(variant)} 应产生不同键`);
  }
});

test('位置键：符号位与分隔符不可省略（0 与 -0、x/y/z 顺序敏感）', () => {
  const a = locationKey({ server: 's', world: 'w', x: 1, y: 23, z: 4, side: 'front' });
  const b = locationKey({ server: 's', world: 'w', x: 12, y: 3, z: 4, side: 'front' });
  assert.notEqual(a, b);
});

test('isLocationKey：只接受 32 位 hex（大小写都收）', () => {
  assert.equal(isLocationKey('7db68f2a217a7bf33438f71dcb0d10e7'), true);
  assert.equal(isLocationKey('7DB68F2A217A7BF33438F71DCB0D10E7'), true);
  assert.equal(isLocationKey('7db68f2a217a7bf33438f71dcb0d10e'), false);
  assert.equal(isLocationKey('z'.repeat(32)), false);
  assert.equal(isLocationKey(null), false);
  assert.equal(isLocationKey(undefined), false);
});
