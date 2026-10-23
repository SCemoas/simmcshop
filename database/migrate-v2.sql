-- =============================================================
-- shop_signs 表结构 v1 → v2 迁移
--
-- 正常情况下**不需要手工执行**：mod v2 在连接成功后会自己完成这些变更
-- （见 DatabaseReporter.migrate()，每一步都先查 information_schema，可重复执行）。
-- 这里提供等价 SQL，供以下场景使用：
--   - 数据库账号没有 ALTER 权限，需要 DBA 代为执行
--   - 想在更新 mod 之前先确认变更内容
--   - 想用备份库演练一遍
--
-- ⚠️ 升级顺序（重要）
--   v2 把 `time` 列改名为 `seen_at`，**旧版客户端 mod 的 INSERT 语句会因此失败**
--   （报 Unknown column 'time'）。这是有意的：宁可让旧客户端上传失败并明确报错，
--   也不要让它写入新旧两套位置键，导致同一個商店在库里出现两行。
--
--   正确顺序：
--     1) 先备份数据库
--     2) 发布新的客户端 mod，让玩家更新（本表可以先不动，旧 mod 继续能写）
--     3) 确认绝大多数玩家已更新后，再执行本脚本（或让新 mod 自动迁移）
--   若不慎先迁移了库，旧客户端会持续报错、数据上传暂停，但本地 txt 仍完整保留，
--   玩家更新 mod 后会自动补传（mod 有重连补传队列）。
--
-- 执行前请把 database_name 换成实际库名，或直接 USE 到目标库。
-- =============================================================

-- -------------------------------------------------------------
-- 1. time → seen_at
--    `time` 是 MySQL 保留字，到处需要反引号；更重要的是这个名字没有表达真实语义：
--    它只在**内容发生变化**时才更新。改为 seen_at。
-- -------------------------------------------------------------
ALTER TABLE `shop_signs` CHANGE COLUMN `time` `seen_at` DATETIME NOT NULL;

-- -------------------------------------------------------------
-- 2. 首次收录时间
--    新列默认给一个哨兵值，随后用既有的 seen_at 回填（老数据无从得知真实首次时间）
-- -------------------------------------------------------------
ALTER TABLE `shop_signs`
  ADD COLUMN `first_seen_at` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'
  COMMENT '首次被收录的时间';
UPDATE `shop_signs` SET `first_seen_at` = `seen_at`
 WHERE `first_seen_at` = '1970-01-01 00:00:00';
ALTER TABLE `shop_signs`
  MODIFY COLUMN `first_seen_at` DATETIME NOT NULL COMMENT '首次被收录的时间';

-- -------------------------------------------------------------
-- 3. 最后被扫到的时间（本次升级的核心）
--
--    原实现里内容没变就不上报，数据库完全不知道商店是否还存在 ——
--    商店被拆掉后，网站会永远显示它。
--
--    mod v2 增加了心跳：每 5 分钟把「当前扫到的商店」批量刷新一次本列。
--    网站据此判断商店是否可能已失效。
--
--    老数据没有心跳记录，只能用 seen_at 兜底（语义上偏保守，会偏旧）。
-- -------------------------------------------------------------
ALTER TABLE `shop_signs`
  ADD COLUMN `last_seen_at` DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00'
  COMMENT '最后一次被玩家扫到的时间（内容没变也会刷新）';
UPDATE `shop_signs` SET `last_seen_at` = `seen_at`
 WHERE `last_seen_at` = '1970-01-01 00:00:00';

-- -------------------------------------------------------------
-- 4. type / side 收敛为 ENUM
--    取值是封闭集合，VARCHAR(16) 既浪费空间又没有任何约束。
--    先修正越界值（防止 MODIFY 失败），再改类型。
-- -------------------------------------------------------------
UPDATE `shop_signs` SET `type` = 'sell'
 WHERE `type` NOT IN ('sell','buy','sold_out','no_space');
ALTER TABLE `shop_signs`
  MODIFY COLUMN `type` ENUM('sell','buy','sold_out','no_space') NOT NULL;

UPDATE `shop_signs` SET `side` = 'front'
 WHERE `side` NOT IN ('front','back');
ALTER TABLE `shop_signs`
  MODIFY COLUMN `side` ENUM('front','back') NOT NULL DEFAULT 'front';

-- -------------------------------------------------------------
-- 5. price: DOUBLE → DECIMAL
--    价格是货币，浮点数在比较与聚合时会产生误差。
-- -------------------------------------------------------------
ALTER TABLE `shop_signs`
  MODIFY COLUMN `price` DECIMAL(20,4) NOT NULL DEFAULT 0;

-- -------------------------------------------------------------
-- 6. location_key 字符集 utf8mb4 → ascii
--    内容恒为 32 位十六进制，按 utf8mb4 存要占 128 字节，改 ascii 后 32 字节，
--    唯一索引体积降到约 1/4。
-- -------------------------------------------------------------
ALTER TABLE `shop_signs`
  MODIFY COLUMN `location_key` CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
  COMMENT 'MD5(服务器|世界|x,y,z|面)，每个商店唯一一行';

-- -------------------------------------------------------------
-- 7. 重算位置键
--
--    v1 的键是 MD5(世界|x,y,z|面)，**不含服务器**。两个不同服务器上
--    同名世界的同坐标同面商店会算出同一个键，ON DUPLICATE KEY UPDATE
--    会让它们互相覆盖。
--
--    这里用与 mod(Java) 完全相同的拼接方式重算。注意 MySQL 的 MD5()
--    同样返回小写十六进制，与 Java 端一致 —— 若不一致，mod 会认为
--    所有商店都是新的，从而重复插入。
-- -------------------------------------------------------------
UPDATE `shop_signs`
   SET `location_key` = MD5(CONCAT(`server`, '|', `world`, '|',
                                   `x`, ',', `y`, ',', `z`, '|', `side`));

-- -------------------------------------------------------------
-- 8. 补齐查询索引
--
--    v1 除了主键与 location_key 唯一键之外**没有任何索引**，
--    网站每一次筛选都是全表扫描 + filesort。
--    下面这些索引对应网站实际的查询形态。
-- -------------------------------------------------------------
ALTER TABLE `shop_signs` ADD INDEX `idx_owner`         (`owner`);
ALTER TABLE `shop_signs` ADD INDEX `idx_type_price`    (`type`, `price`);
ALTER TABLE `shop_signs` ADD INDEX `idx_seen_at`       (`seen_at`);
ALTER TABLE `shop_signs` ADD INDEX `idx_last_seen_at`  (`last_seen_at`);
ALTER TABLE `shop_signs` ADD INDEX `idx_item`          (`item`(64));
ALTER TABLE `shop_signs` ADD INDEX `idx_server_world`  (`server`(48), `world`(48));

-- =============================================================
-- 可选：大表下的物品名检索
--
-- 网站的「物品名」筛选用的是 LIKE '%关键词%'，前导通配符无法用普通索引。
-- 如果表已经很大（十万行以上）且物品检索很频繁，可以改用全文索引：
--
--   ALTER TABLE `shop_signs` ADD FULLTEXT INDEX ft_item (`item`) WITH PARSER ngram;
--
-- 对应地把网站的 SHOP_ITEM_SEARCH 切成 fulltext（见网站 README）。
-- 注意 ngram 的分词行为与 LIKE 不完全一致（中文按 2 字切分），
-- 搜索语义会略有变化，因此默认不开启。
-- =============================================================

-- 检查迁移结果（应全部符合预期）
-- SHOW CREATE TABLE `shop_signs`;
-- SELECT COUNT(*) FROM `shop_signs`
--  WHERE `location_key` <> MD5(CONCAT(`server`,'|',`world`,'|',`x`,',',`y`,',',`z`,'|',`side`));
-- 上面这条应当返回 0。

-- =============================================================
-- v2 → v2.1 增量（2.1.0 客户端会自动执行；此处为等价 SQL）
--
-- 纯增量：只加列与索引，不改任何现有列，v2.0.0 客户端写入不受影响。
-- 网站可以按 land 列筛选领地（等价于早期分表方案的切面），
-- gateway 列记录离商店最近的港口/驿站（来自地图站 transport_gateways 图层）。
-- =============================================================

ALTER TABLE `shop_signs`
  ADD COLUMN `land` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '所属领地（Lands），野外为空串';
ALTER TABLE `shop_signs`
  ADD COLUMN `gateway` VARCHAR(192) NOT NULL DEFAULT '' COMMENT '最近港口/驿站及距离';
ALTER TABLE `shop_signs` ADD INDEX `idx_land` (`land`);
