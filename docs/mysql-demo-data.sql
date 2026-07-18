-- DBStudio 本地 MySQL 演示数据
-- 目标实例：localhost:33061（执行时由命令行传入凭据）

SET NAMES utf8mb4;
SET SESSION cte_max_recursion_depth = 20000;
SET FOREIGN_KEY_CHECKS = 0;

DROP DATABASE IF EXISTS devdb;
DROP DATABASE IF EXISTS sitdb;
DROP DATABASE IF EXISTS uatdb;

CREATE DATABASE devdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE sitdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE uatdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE devdb.sys_user (
  user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
  username VARCHAR(64) NOT NULL COMMENT '登录用户名',
  password_hash CHAR(64) NOT NULL COMMENT '密码哈希值',
  real_name VARCHAR(80) NOT NULL COMMENT '真实姓名',
  gender ENUM('未知','男','女') NOT NULL DEFAULT '未知' COMMENT '性别',
  age TINYINT UNSIGNED NULL COMMENT '年龄',
  phone CHAR(11) NULL COMMENT '手机号码',
  email VARCHAR(128) NULL COMMENT '电子邮箱',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：0停用，1正常，2锁定',
  preferences JSON NULL COMMENT '用户偏好设置',
  biography TEXT NULL COMMENT '个人简介',
  avatar MEDIUMBLOB NULL COMMENT '头像二进制数据',
  birthday DATE NULL COMMENT '出生日期',
  last_login_at DATETIME(3) NULL COMMENT '最后登录时间',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_sys_user_username (username),
  KEY idx_sys_user_status_created (status, created_at)
) ENGINE=InnoDB COMMENT='系统用户表';

CREATE TABLE devdb.product (
  product_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键',
  sku_code VARCHAR(40) NOT NULL COMMENT '商品库存编码',
  product_name VARCHAR(160) NOT NULL COMMENT '商品名称',
  category_id INT UNSIGNED NOT NULL COMMENT '分类编号',
  unit_price DECIMAL(12,2) NOT NULL COMMENT '销售单价',
  cost_price DECIMAL(12,4) NOT NULL COMMENT '成本单价',
  weight_kg FLOAT NULL COMMENT '商品重量（千克）',
  rating DOUBLE NULL COMMENT '商品综合评分',
  stock_quantity MEDIUMINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '库存数量',
  warning_quantity SMALLINT UNSIGNED NOT NULL DEFAULT 10 COMMENT '预警库存数量',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
  flags BIT(8) NOT NULL DEFAULT b'00000000' COMMENT '商品位标记',
  tags SET('新品','热销','推荐','清仓') NULL COMMENT '商品标签集合',
  detail MEDIUMTEXT NULL COMMENT '商品详细说明',
  attributes JSON NULL COMMENT '商品扩展属性',
  shelf_year YEAR NULL COMMENT '上架年份',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (product_id),
  UNIQUE KEY uk_product_sku (sku_code),
  FULLTEXT KEY ft_product_name_detail (product_name, detail)
) ENGINE=InnoDB COMMENT='商品信息表';

CREATE TABLE devdb.sales_order (
  order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单主键',
  order_no CHAR(24) NOT NULL COMMENT '订单编号',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '下单用户主键',
  order_status ENUM('待支付','已支付','已发货','已完成','已取消') NOT NULL COMMENT '订单状态',
  payment_method VARCHAR(20) NULL COMMENT '支付方式',
  total_amount DECIMAL(14,2) NOT NULL COMMENT '订单总金额',
  discount_amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '优惠金额',
  payable_amount DECIMAL(14,2) GENERATED ALWAYS AS (total_amount - discount_amount) STORED COMMENT '应付金额',
  consignee VARCHAR(80) NOT NULL COMMENT '收货人姓名',
  shipping_address VARCHAR(255) NOT NULL COMMENT '收货地址',
  customer_remark TEXT NULL COMMENT '客户备注',
  paid_at DATETIME NULL COMMENT '支付时间',
  delivery_time TIME NULL COMMENT '期望送达时刻',
  created_at DATETIME NOT NULL COMMENT '下单时间',
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_sales_order_no (order_no),
  KEY idx_sales_order_user_created (user_id, created_at),
  CONSTRAINT fk_sales_order_user FOREIGN KEY (user_id) REFERENCES devdb.sys_user (user_id)
) ENGINE=InnoDB COMMENT='销售订单主表';

CREATE TABLE devdb.sales_order_item (
  item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单明细主键',
  order_id BIGINT UNSIGNED NOT NULL COMMENT '订单主键',
  product_id BIGINT UNSIGNED NOT NULL COMMENT '商品主键',
  quantity SMALLINT UNSIGNED NOT NULL COMMENT '购买数量',
  unit_price DECIMAL(12,2) NOT NULL COMMENT '成交单价',
  line_amount DECIMAL(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED COMMENT '明细金额',
  product_snapshot JSON NULL COMMENT '商品快照',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (item_id),
  KEY idx_order_item_order (order_id),
  KEY idx_order_item_product (product_id),
  CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES devdb.sales_order (order_id),
  CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES devdb.product (product_id)
) ENGINE=InnoDB COMMENT='销售订单明细表';

CREATE TABLE devdb.audit_log (
  log_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  trace_id BINARY(16) NOT NULL COMMENT '链路追踪标识',
  module VARCHAR(40) NOT NULL COMMENT '业务模块',
  operation VARCHAR(80) NOT NULL COMMENT '操作名称',
  request_ip VARBINARY(16) NULL COMMENT '请求IP二进制值',
  request_body LONGTEXT NULL COMMENT '请求报文',
  response_body LONGTEXT NULL COMMENT '响应报文',
  context_data JSON NULL COMMENT '日志上下文数据',
  success BOOLEAN NOT NULL COMMENT '是否成功',
  elapsed_ms INT UNSIGNED NOT NULL COMMENT '耗时毫秒数',
  occurred_at TIMESTAMP(6) NOT NULL COMMENT '发生时间',
  PRIMARY KEY (log_id),
  KEY idx_audit_log_occurred (occurred_at),
  KEY idx_audit_log_module_success (module, success)
) ENGINE=InnoDB COMMENT='系统审计日志表';

CREATE TABLE devdb.file_asset (
  file_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文件主键',
  file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
  mime_type VARCHAR(100) NOT NULL COMMENT '媒体类型',
  file_size BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
  checksum BINARY(32) NOT NULL COMMENT '文件校验摘要',
  thumbnail TINYBLOB NULL COMMENT '缩略图数据',
  preview_data BLOB NULL COMMENT '预览数据',
  file_content LONGBLOB NULL COMMENT '文件内容',
  description TINYTEXT NULL COMMENT '文件简短说明',
  uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (file_id)
) ENGINE=InnoDB COMMENT='文件资源表';

CREATE TABLE devdb.data_type_sample (
  sample_id INT NOT NULL AUTO_INCREMENT COMMENT '样例主键',
  signed_tiny TINYINT NULL COMMENT '有符号微整数样例',
  unsigned_small SMALLINT UNSIGNED NULL COMMENT '无符号小整数样例',
  signed_medium MEDIUMINT NULL COMMENT '有符号中整数样例',
  signed_integer INT NULL COMMENT '有符号整数样例',
  signed_big BIGINT NULL COMMENT '有符号大整数样例',
  fixed_number DECIMAL(20,6) NULL COMMENT '高精度定点数样例',
  float_number FLOAT NULL COMMENT '单精度浮点数样例',
  double_number DOUBLE NULL COMMENT '双精度浮点数样例',
  fixed_char CHAR(10) NULL COMMENT '定长字符串样例',
  variable_char VARCHAR(255) NULL COMMENT '变长字符串样例',
  tiny_text TINYTEXT NULL COMMENT '微型文本样例',
  normal_text TEXT NULL COMMENT '普通文本样例',
  medium_text MEDIUMTEXT NULL COMMENT '中型文本样例',
  long_text LONGTEXT NULL COMMENT '长文本样例',
  fixed_binary BINARY(8) NULL COMMENT '定长二进制样例',
  variable_binary VARBINARY(64) NULL COMMENT '变长二进制样例',
  tiny_blob TINYBLOB NULL COMMENT '微型二进制对象样例',
  normal_blob BLOB NULL COMMENT '普通二进制对象样例',
  medium_blob MEDIUMBLOB NULL COMMENT '中型二进制对象样例',
  long_blob LONGBLOB NULL COMMENT '大型二进制对象样例',
  bit_value BIT(16) NULL COMMENT '位值样例',
  bool_value BOOLEAN NULL COMMENT '布尔值样例',
  json_value JSON NULL COMMENT 'JSON文档样例',
  enum_value ENUM('甲','乙','丙') NULL COMMENT '枚举值样例',
  set_value SET('读','写','执行') NULL COMMENT '集合值样例',
  date_value DATE NULL COMMENT '日期样例',
  time_value TIME(3) NULL COMMENT '时间样例',
  datetime_value DATETIME(3) NULL COMMENT '日期时间样例',
  timestamp_value TIMESTAMP(3) NULL COMMENT '时间戳样例',
  year_value YEAR NULL COMMENT '年份样例',
  point_value POINT NULL COMMENT '地理坐标点样例',
  nullable_value VARCHAR(20) NULL COMMENT '可空字段样例',
  PRIMARY KEY (sample_id)
) ENGINE=InnoDB COMMENT='MySQL常见字段类型样例表';

INSERT INTO devdb.sys_user
  (username, password_hash, real_name, gender, age, phone, email, status, preferences, biography, birthday, last_login_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000
)
SELECT CONCAT('user_', LPAD(n, 5, '0')), SHA2(CONCAT('password-', n), 256), CONCAT('测试用户', n),
       ELT(MOD(n, 3) + 1, '未知', '男', '女'), 18 + MOD(n, 43), CONCAT('13', LPAD(MOD(n * 7919, 1000000000), 9, '0')),
       CONCAT('user', n, '@example.com'), IF(MOD(n, 17) = 0, 0, 1),
       JSON_OBJECT('theme', IF(MOD(n, 2) = 0, 'dark', 'light'), 'language', 'zh-CN'),
       CONCAT('这是第', n, '位测试用户的个人简介，用于验证TEXT字段的展示、编辑与检索。'),
       DATE_ADD('1970-01-01', INTERVAL MOD(n * 37, 12000) DAY),
       DATE_SUB(NOW(3), INTERVAL MOD(n, 720) HOUR)
FROM seq;

INSERT INTO devdb.product
  (sku_code, product_name, category_id, unit_price, cost_price, weight_kg, rating, stock_quantity, warning_quantity, enabled, flags, tags, detail, attributes, shelf_year)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 800
)
SELECT CONCAT('SKU', LPAD(n, 8, '0')), CONCAT('演示商品-', n), MOD(n, 20) + 1,
       ROUND(9.90 + MOD(n * 131, 30000) / 100, 2), ROUND(5.1234 + MOD(n * 97, 20000) / 100, 4),
       ROUND(0.1 + MOD(n, 150) / 10, 2), ROUND(3 + MOD(n, 21) / 10, 1), MOD(n * 19, 5000), 20, MOD(n, 29) <> 0,
       n & 255, IF(MOD(n, 5) = 0, '新品,推荐', '热销'),
       CONCAT('商品', n, '的详细说明。这里包含较长的中文TEXT内容，可用于全文检索和大文本编辑测试。'),
       JSON_OBJECT('颜色', ELT(MOD(n, 3) + 1, '红色', '蓝色', '黑色'), '尺寸', ELT(MOD(n, 4) + 1, 'S', 'M', 'L', 'XL')),
       2020 + MOD(n, 7)
FROM seq;

INSERT INTO devdb.sales_order
  (order_no, user_id, order_status, payment_method, total_amount, discount_amount, consignee, shipping_address, customer_remark, paid_at, delivery_time, created_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 6000
)
SELECT CONCAT('DD', DATE_FORMAT(CURRENT_DATE, '%Y%m%d'), LPAD(n, 14, '0')), MOD(n - 1, 2000) + 1,
       ELT(MOD(n, 5) + 1, '待支付', '已支付', '已发货', '已完成', '已取消'),
       ELT(MOD(n, 3) + 1, '支付宝', '微信支付', '银行卡'), ROUND(30 + MOD(n * 173, 100000) / 100, 2),
       ROUND(MOD(n, 50) / 10, 2), CONCAT('收货人', MOD(n, 2000) + 1),
       CONCAT('广东省深圳市南山区科技园演示路', MOD(n, 999) + 1, '号'),
       IF(MOD(n, 9) = 0, CONCAT('订单', n, '请优先配送，送达前电话联系。'), NULL),
       IF(MOD(n, 5) = 0, NULL, DATE_SUB(NOW(), INTERVAL MOD(n, 180) DAY)),
       MAKETIME(9 + MOD(n, 10), MOD(n, 60), 0), DATE_SUB(NOW(), INTERVAL MOD(n, 365) DAY)
FROM seq;

INSERT INTO devdb.sales_order_item
  (order_id, product_id, quantity, unit_price, product_snapshot)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 15000
)
SELECT MOD(n - 1, 6000) + 1, MOD(n * 17 - 1, 800) + 1, MOD(n, 5) + 1,
       ROUND(9.90 + MOD(n * 131, 30000) / 100, 2),
       JSON_OBJECT('商品名称', CONCAT('演示商品-', MOD(n * 17 - 1, 800) + 1), '下单序号', n)
FROM seq;

INSERT INTO devdb.audit_log
  (trace_id, module, operation, request_ip, request_body, response_body, context_data, success, elapsed_ms, occurred_at)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000
)
SELECT UNHEX(MD5(CONCAT('trace-', n))), ELT(MOD(n, 4) + 1, '用户中心', '商品中心', '订单中心', '支付中心'),
       ELT(MOD(n, 4) + 1, '查询', '新增', '修改', '删除'), INET6_ATON(CONCAT('10.0.', MOD(n, 255), '.', MOD(n * 7, 255))),
       CONCAT('{"请求序号":', n, ',"说明":"中文请求报文测试"}'),
       CONCAT('{"代码":', IF(MOD(n, 23) = 0, 500, 200), ',"消息":"', IF(MOD(n, 23) = 0, '处理失败', '处理成功'), '"}'),
       JSON_OBJECT('操作人', CONCAT('user_', LPAD(MOD(n, 2000) + 1, 5, '0')), '环境', 'dev'),
       MOD(n, 23) <> 0, MOD(n * 47, 3000), TIMESTAMPADD(MICROSECOND, n, DATE_SUB(NOW(6), INTERVAL MOD(n, 90) DAY))
FROM seq;

INSERT INTO devdb.file_asset
  (file_name, mime_type, file_size, checksum, thumbnail, preview_data, file_content, description)
VALUES
  ('中文说明.txt', 'text/plain', 36, UNHEX(SHA2('中文说明.txt', 256)), _binary 'thumb', _binary 'preview', _binary '这是演示文件内容', '文本文件样例'),
  ('报表数据.csv', 'text/csv', 128, UNHEX(SHA2('报表数据.csv', 256)), NULL, _binary '编号,名称', _binary '1,测试数据', 'CSV文件样例'),
  ('产品图片.png', 'image/png', 2048, UNHEX(SHA2('产品图片.png', 256)), _binary 'tiny-image', _binary 'image-preview', _binary 'binary-image-content', '图片文件样例');

INSERT INTO devdb.data_type_sample
  (signed_tiny, unsigned_small, signed_medium, signed_integer, signed_big, fixed_number, float_number, double_number,
   fixed_char, variable_char, tiny_text, normal_text, medium_text, long_text, fixed_binary, variable_binary,
   tiny_blob, normal_blob, medium_blob, long_blob, bit_value, bool_value, json_value, enum_value, set_value,
   date_value, time_value, datetime_value, timestamp_value, year_value, point_value, nullable_value)
VALUES
  (-128, 65535, -8388608, -2147483648, -9223372036854775808, 12345678901234.123456, 3.14, 2.718281828459,
   '中文定长', '中文、English、emoji😀', '短文本', '普通TEXT中文内容', '中型TEXT中文内容', '大型TEXT中文内容',
   _binary '12345678', _binary '可变二进制', _binary 'tiny', _binary 'blob', _binary 'medium-blob', _binary 'long-blob',
   b'1010101010101010', TRUE, JSON_OBJECT('中文键', '中文值', '数组', JSON_ARRAY(1, 2, 3)), '甲', '读,写',
   '2026-07-18', '17:30:15.123', '2026-07-18 17:30:15.123', '2026-07-18 17:30:15.123', 2026, ST_PointFromText('POINT(114.0579 22.5431)'), NULL),
  (127, 0, 8388607, 2147483647, 9223372036854775807, -999.000001, -1.5, 1.0E100,
   'ABC', '', '', NULL, REPEAT('中型文本', 100), REPEAT('大型文本', 1000),
   _binary '\0\0\0\0\0\0\0\0', X'00FF10', X'01', X'001122', X'00112233', X'001122334455',
   b'0000000000000001', FALSE, JSON_ARRAY('甲', '乙'), '丙', '执行',
   '2000-02-29', '00:00:00.000', '2000-02-29 00:00:00.000', CURRENT_TIMESTAMP(3), 2000, ST_PointFromText('POINT(113.7518 23.0207)'), '非空样例');

-- 将开发库的表结构完整复制到 SIT/UAT；CREATE TABLE LIKE 会保留字段、索引及中文注释。
CREATE TABLE sitdb.sys_user LIKE devdb.sys_user;
CREATE TABLE sitdb.product LIKE devdb.product;
CREATE TABLE sitdb.sales_order LIKE devdb.sales_order;
CREATE TABLE sitdb.sales_order_item LIKE devdb.sales_order_item;
CREATE TABLE sitdb.audit_log LIKE devdb.audit_log;
CREATE TABLE sitdb.file_asset LIKE devdb.file_asset;
CREATE TABLE sitdb.data_type_sample LIKE devdb.data_type_sample;

CREATE TABLE uatdb.sys_user LIKE devdb.sys_user;
CREATE TABLE uatdb.product LIKE devdb.product;
CREATE TABLE uatdb.sales_order LIKE devdb.sales_order;
CREATE TABLE uatdb.sales_order_item LIKE devdb.sales_order_item;
CREATE TABLE uatdb.audit_log LIKE devdb.audit_log;
CREATE TABLE uatdb.file_asset LIKE devdb.file_asset;
CREATE TABLE uatdb.data_type_sample LIKE devdb.data_type_sample;

INSERT INTO sitdb.sys_user SELECT * FROM devdb.sys_user WHERE user_id <= 800;
INSERT INTO sitdb.product SELECT * FROM devdb.product WHERE product_id <= 400;
INSERT INTO sitdb.sales_order
  (order_id, order_no, user_id, order_status, payment_method, total_amount, discount_amount, consignee, shipping_address, customer_remark, paid_at, delivery_time, created_at)
  SELECT order_id, order_no, user_id, order_status, payment_method, total_amount, discount_amount, consignee, shipping_address, customer_remark, paid_at, delivery_time, created_at
  FROM devdb.sales_order WHERE order_id <= 2000;
INSERT INTO sitdb.sales_order_item (item_id, order_id, product_id, quantity, unit_price, product_snapshot, created_at)
  SELECT item_id, order_id, product_id, quantity, unit_price, product_snapshot, created_at
  FROM devdb.sales_order_item WHERE order_id <= 2000 AND product_id <= 400 LIMIT 3500;
INSERT INTO sitdb.audit_log SELECT * FROM devdb.audit_log WHERE log_id <= 1000;
INSERT INTO sitdb.file_asset SELECT * FROM devdb.file_asset;
INSERT INTO sitdb.data_type_sample SELECT * FROM devdb.data_type_sample;

INSERT INTO uatdb.sys_user SELECT * FROM devdb.sys_user WHERE user_id <= 300;
INSERT INTO uatdb.product SELECT * FROM devdb.product WHERE product_id <= 200;
INSERT INTO uatdb.sales_order
  (order_id, order_no, user_id, order_status, payment_method, total_amount, discount_amount, consignee, shipping_address, customer_remark, paid_at, delivery_time, created_at)
  SELECT order_id, order_no, user_id, order_status, payment_method, total_amount, discount_amount, consignee, shipping_address, customer_remark, paid_at, delivery_time, created_at
  FROM devdb.sales_order WHERE order_id <= 600;
INSERT INTO uatdb.sales_order_item (item_id, order_id, product_id, quantity, unit_price, product_snapshot, created_at)
  SELECT item_id, order_id, product_id, quantity, unit_price, product_snapshot, created_at
  FROM devdb.sales_order_item WHERE order_id <= 600 AND product_id <= 200 LIMIT 1000;
INSERT INTO uatdb.audit_log SELECT * FROM devdb.audit_log WHERE log_id <= 300;
INSERT INTO uatdb.file_asset SELECT * FROM devdb.file_asset;
INSERT INTO uatdb.data_type_sample SELECT * FROM devdb.data_type_sample;

SET FOREIGN_KEY_CHECKS = 1;
