CREATE DATABASE IF NOT EXISTS warehouse;
USE warehouse;

CREATE TABLE IF NOT EXISTS customers (
    customer_id BIGINT,
    customer_name VARCHAR(120),
    customer_segment VARCHAR(32),
    region VARCHAR(32),
    created_at DATETIME
)
ENGINE=OLAP
DUPLICATE KEY(customer_id)
DISTRIBUTED BY HASH(customer_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS products (
    product_id BIGINT,
    sku VARCHAR(64),
    product_name VARCHAR(120),
    category VARCHAR(64),
    unit_price DECIMAL(12, 2),
    active BOOLEAN
)
ENGINE=OLAP
DUPLICATE KEY(product_id)
DISTRIBUTED BY HASH(product_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS orders (
    order_id BIGINT,
    customer_id BIGINT,
    order_status VARCHAR(24),
    payment_method VARCHAR(32),
    placed_at DATETIME,
    updated_at DATETIME
)
ENGINE=OLAP
DUPLICATE KEY(order_id)
DISTRIBUTED BY HASH(order_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS order_items (
    order_item_id BIGINT,
    order_id BIGINT,
    product_id BIGINT,
    quantity INT,
    unit_price DECIMAL(12, 2),
    discount_percent DECIMAL(5, 2)
)
ENGINE=OLAP
DUPLICATE KEY(order_item_id)
DISTRIBUTED BY HASH(order_item_id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS dim_date (
    date_key DATE,
    fiscal_week VARCHAR(16),
    fiscal_month VARCHAR(16),
    fiscal_quarter VARCHAR(16),
    is_weekend BOOLEAN
)
ENGINE=OLAP
DUPLICATE KEY(date_key)
DISTRIBUTED BY HASH(date_key) BUCKETS 1
PROPERTIES ("replication_num" = "1");

CREATE TABLE IF NOT EXISTS fact_daily_sales (
    date_key DATE,
    gross_revenue DECIMAL(14, 2),
    net_revenue DECIMAL(14, 2),
    order_count INT,
    item_count INT,
    distinct_customers INT,
    avg_order_value DECIMAL(14, 2)
)
ENGINE=OLAP
DUPLICATE KEY(date_key)
DISTRIBUTED BY HASH(date_key) BUCKETS 1
PROPERTIES ("replication_num" = "1");

TRUNCATE TABLE customers;
TRUNCATE TABLE products;
TRUNCATE TABLE orders;
TRUNCATE TABLE order_items;
TRUNCATE TABLE dim_date;
TRUNCATE TABLE fact_daily_sales;

INSERT INTO customers VALUES
    (1001, 'Balin Mining Co', 'enterprise', 'north', '2025-11-12 09:14:00'),
    (1002, 'Dori & Sons', 'mid-market', 'east', '2025-12-04 12:20:00'),
    (1003, 'Erebor Imports', 'enterprise', 'west', '2025-12-18 16:05:00'),
    (1004, 'Blue Mountain Crafts', 'small-business', 'south', '2026-01-02 08:10:00'),
    (1005, 'Khazad Freight', 'mid-market', 'north', '2026-01-06 10:32:00'),
    (1006, 'Iron Hills Outfitter', 'small-business', 'west', '2026-01-10 14:44:00');

INSERT INTO products VALUES
    (2001, 'PICK-STD-01', 'Standard Pickaxe', 'tools', 89.00, TRUE),
    (2002, 'PICK-PRM-01', 'Reinforced Pickaxe', 'tools', 149.00, TRUE),
    (2003, 'LAMP-LED-01', 'Mine Lantern', 'lighting', 49.00, TRUE),
    (2004, 'HELM-GLD-01', 'Guild Helmet', 'safety', 129.00, TRUE),
    (2005, 'ROPE-STL-01', 'Steel Rope 40m', 'safety', 79.00, TRUE),
    (2006, 'CART-TRK-01', 'Track Cart', 'transport', 399.00, TRUE);

INSERT INTO orders VALUES
    (3001, 1001, 'DELIVERED', 'wire_transfer', '2026-02-01 09:12:00', '2026-02-03 11:20:00'),
    (3002, 1002, 'DELIVERED', 'credit_card', '2026-02-01 13:05:00', '2026-02-04 16:40:00'),
    (3003, 1003, 'SHIPPED', 'wire_transfer', '2026-02-02 08:44:00', '2026-02-02 12:11:00'),
    (3004, 1001, 'PAID', 'wire_transfer', '2026-02-02 17:19:00', '2026-02-02 18:02:00'),
    (3005, 1004, 'CANCELED', 'credit_card', '2026-02-03 10:25:00', '2026-02-03 13:51:00'),
    (3006, 1005, 'DELIVERED', 'ach', '2026-02-03 14:36:00', '2026-02-05 09:18:00'),
    (3007, 1002, 'DELIVERED', 'credit_card', '2026-02-04 11:04:00', '2026-02-06 15:20:00'),
    (3008, 1006, 'PAID', 'credit_card', '2026-02-05 07:42:00', '2026-02-05 08:10:00'),
    (3009, 1003, 'DELIVERED', 'wire_transfer', '2026-02-05 19:10:00', '2026-02-07 10:45:00'),
    (3010, 1005, 'SHIPPED', 'ach', '2026-02-06 09:30:00', '2026-02-06 12:12:00');

INSERT INTO order_items VALUES
    (4001, 3001, 2001, 4, 89.00, 5.00),
    (4002, 3001, 2003, 6, 49.00, 0.00),
    (4003, 3002, 2002, 2, 149.00, 3.00),
    (4004, 3002, 2004, 2, 129.00, 0.00),
    (4005, 3003, 2006, 1, 399.00, 8.00),
    (4006, 3003, 2005, 5, 79.00, 2.00),
    (4007, 3004, 2001, 1, 89.00, 0.00),
    (4008, 3004, 2005, 3, 79.00, 5.00),
    (4009, 3005, 2004, 1, 129.00, 0.00),
    (4010, 3006, 2003, 10, 49.00, 6.00),
    (4011, 3006, 2005, 4, 79.00, 4.00),
    (4012, 3007, 2001, 2, 89.00, 0.00),
    (4013, 3007, 2002, 1, 149.00, 0.00),
    (4014, 3008, 2004, 3, 129.00, 7.00),
    (4015, 3009, 2006, 2, 399.00, 4.00),
    (4016, 3009, 2003, 6, 49.00, 0.00),
    (4017, 3010, 2001, 8, 89.00, 9.00),
    (4018, 3010, 2005, 8, 79.00, 7.00);

INSERT INTO dim_date VALUES
    ('2026-02-01', '2026-W05', '2026-02', '2026-Q1', TRUE),
    ('2026-02-02', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-03', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-04', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-05', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-06', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-07', '2026-W06', '2026-02', '2026-Q1', TRUE);

INSERT INTO fact_daily_sales
SELECT
    DATE(placed_at) AS date_key,
    ROUND(SUM(quantity * unit_price), 2) AS gross_revenue,
    ROUND(SUM(quantity * unit_price * (1 - (discount_percent / 100))), 2) AS net_revenue,
    COUNT(DISTINCT order_id) AS order_count,
    SUM(quantity) AS item_count,
    COUNT(DISTINCT customer_id) AS distinct_customers,
    ROUND(
        SUM(quantity * unit_price * (1 - (discount_percent / 100))) /
            NULLIF(COUNT(DISTINCT order_id), 0),
        2
    ) AS avg_order_value
FROM orders o
JOIN order_items oi
    ON oi.order_id = o.order_id
WHERE o.order_status IN ('PAID', 'SHIPPED', 'DELIVERED')
GROUP BY DATE(placed_at);

CREATE USER IF NOT EXISTS 'readonly'@'%' IDENTIFIED BY 'readonly';
GRANT USAGE ON ALL CATALOGS TO USER 'readonly'@'%';
GRANT SELECT ON ALL TABLES IN DATABASE warehouse TO USER 'readonly'@'%';
