-- ============================================================
-- MySQL 初始化：一次建好"单体库 + 六个微服务库"
-- 只在数据卷首次初始化时执行（/docker-entrypoint-initdb.d）
-- ============================================================
-- 为什么两套库都要建：
--   单体模式（polaris.mode=local）    → 一个 polaris_mono 库装全部表
--   微服务模式（polaris.mode=micro）  → 六个服务各自的库
-- 两种模式共存是刻意的：案例的讲法是"先单体跑通，再逐个拆出去"，
-- 如果两次演示要用两套 compose，读者就无法比较"同一份代码在两种装配下的行为差异"。
-- ============================================================

CREATE DATABASE IF NOT EXISTS polaris_mono
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_order
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_stock
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_pay
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_member
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_promotion
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS polaris_notify
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 应用账号：不要用 root 连业务库（ch18 的最小权限原则）
CREATE USER IF NOT EXISTS 'polaris'@'%' IDENTIFIED BY 'polaris';

-- 授到"库级"而不是全局：
--   GRANT ALL ON *.* TO 'polaris'  → 一旦有注入点，可以跨库拖数据
--   下面这种写法即使被注入，能碰到的也只有 polaris_* 这 7 个库
GRANT ALL PRIVILEGES ON polaris_mono.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_order.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_stock.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_pay.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_member.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_promotion.* TO 'polaris'@'%';
GRANT ALL PRIVILEGES ON polaris_notify.* TO 'polaris'@'%';

-- 注意：Flyway 需要 CREATE / ALTER / DROP（迁移脚本要建表），
-- 所以这里给了 ALL。生产上的正确做法是"迁移用独立账号，
-- 运行用只有 SELECT/INSERT/UPDATE/DELETE 的账号"，
-- 这样一次注入拿不到 DROP TABLE 的能力。

FLUSH PRIVILEGES;
