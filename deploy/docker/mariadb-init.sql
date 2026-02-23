CREATE TABLE IF NOT EXISTS warehouse.customers (
    customer_id BIGINT PRIMARY KEY,
    customer_name VARCHAR(120) NOT NULL,
    customer_segment VARCHAR(32) NOT NULL,
    region VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS warehouse.products (
    product_id BIGINT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    product_name VARCHAR(120) NOT NULL,
    category VARCHAR(64) NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS warehouse.orders (
    order_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    placed_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES warehouse.customers(customer_id),
    INDEX idx_orders_customer (customer_id),
    INDEX idx_orders_placed_at (placed_at),
    INDEX idx_orders_status (order_status)
);

CREATE TABLE IF NOT EXISTS warehouse.order_items (
    order_item_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    discount_percent DECIMAL(5, 2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES warehouse.orders(order_id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES warehouse.products(product_id),
    INDEX idx_order_items_order (order_id),
    INDEX idx_order_items_product (product_id)
);

CREATE TABLE IF NOT EXISTS warehouse.inventory_snapshots (
    snapshot_id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    snapshot_at DATETIME NOT NULL,
    on_hand_qty INT NOT NULL,
    reserved_qty INT NOT NULL,
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES warehouse.products(product_id),
    INDEX idx_inventory_snapshot (snapshot_at),
    INDEX idx_inventory_product (product_id)
);

CREATE TABLE IF NOT EXISTS warehouse.dim_date (
    date_key DATE PRIMARY KEY,
    fiscal_week VARCHAR(16) NOT NULL,
    fiscal_month VARCHAR(16) NOT NULL,
    fiscal_quarter VARCHAR(16) NOT NULL,
    is_weekend BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS warehouse.fact_daily_sales (
    date_key DATE PRIMARY KEY,
    gross_revenue DECIMAL(14, 2) NOT NULL,
    net_revenue DECIMAL(14, 2) NOT NULL,
    order_count INT NOT NULL,
    item_count INT NOT NULL,
    distinct_customers INT NOT NULL,
    avg_order_value DECIMAL(14, 2) NOT NULL,
    CONSTRAINT fk_fact_daily_sales_date FOREIGN KEY (date_key) REFERENCES warehouse.dim_date(date_key)
);

INSERT INTO warehouse.customers (customer_id, customer_name, customer_segment, region, created_at) VALUES
    (1001, 'Balin Mining Co', 'enterprise', 'north', '2025-11-12 09:14:00'),
    (1002, 'Dori & Sons', 'mid-market', 'east', '2025-12-04 12:20:00'),
    (1003, 'Erebor Imports', 'enterprise', 'west', '2025-12-18 16:05:00'),
    (1004, 'Blue Mountain Crafts', 'small-business', 'south', '2026-01-02 08:10:00'),
    (1005, 'Khazad Freight', 'mid-market', 'north', '2026-01-06 10:32:00'),
    (1006, 'Iron Hills Outfitter', 'small-business', 'west', '2026-01-10 14:44:00')
ON DUPLICATE KEY UPDATE
    customer_name = VALUES(customer_name),
    customer_segment = VALUES(customer_segment),
    region = VALUES(region),
    created_at = VALUES(created_at);

INSERT INTO warehouse.products (product_id, sku, product_name, category, unit_price, active) VALUES
    (2001, 'PICK-STD-01', 'Standard Pickaxe', 'tools', 89.00, TRUE),
    (2002, 'PICK-PRM-01', 'Reinforced Pickaxe', 'tools', 149.00, TRUE),
    (2003, 'LAMP-LED-01', 'Mine Lantern', 'lighting', 49.00, TRUE),
    (2004, 'HELM-GLD-01', 'Guild Helmet', 'safety', 129.00, TRUE),
    (2005, 'ROPE-STL-01', 'Steel Rope 40m', 'safety', 79.00, TRUE),
    (2006, 'CART-TRK-01', 'Track Cart', 'transport', 399.00, TRUE)
ON DUPLICATE KEY UPDATE
    sku = VALUES(sku),
    product_name = VALUES(product_name),
    category = VALUES(category),
    unit_price = VALUES(unit_price),
    active = VALUES(active);

INSERT INTO warehouse.orders (order_id, customer_id, order_status, payment_method, placed_at, updated_at) VALUES
    (3001, 1001, 'DELIVERED', 'wire_transfer', '2026-02-01 09:12:00', '2026-02-03 11:20:00'),
    (3002, 1002, 'DELIVERED', 'credit_card', '2026-02-01 13:05:00', '2026-02-04 16:40:00'),
    (3003, 1003, 'SHIPPED', 'wire_transfer', '2026-02-02 08:44:00', '2026-02-02 12:11:00'),
    (3004, 1001, 'PAID', 'wire_transfer', '2026-02-02 17:19:00', '2026-02-02 18:02:00'),
    (3005, 1004, 'CANCELED', 'credit_card', '2026-02-03 10:25:00', '2026-02-03 13:51:00'),
    (3006, 1005, 'DELIVERED', 'ach', '2026-02-03 14:36:00', '2026-02-05 09:18:00'),
    (3007, 1002, 'DELIVERED', 'credit_card', '2026-02-04 11:04:00', '2026-02-06 15:20:00'),
    (3008, 1006, 'PAID', 'credit_card', '2026-02-05 07:42:00', '2026-02-05 08:10:00'),
    (3009, 1003, 'DELIVERED', 'wire_transfer', '2026-02-05 19:10:00', '2026-02-07 10:45:00'),
    (3010, 1005, 'SHIPPED', 'ach', '2026-02-06 09:30:00', '2026-02-06 12:12:00')
ON DUPLICATE KEY UPDATE
    customer_id = VALUES(customer_id),
    order_status = VALUES(order_status),
    payment_method = VALUES(payment_method),
    placed_at = VALUES(placed_at),
    updated_at = VALUES(updated_at);

INSERT INTO warehouse.order_items (order_item_id, order_id, product_id, quantity, unit_price, discount_percent) VALUES
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
    (4018, 3010, 2005, 8, 79.00, 7.00)
ON DUPLICATE KEY UPDATE
    order_id = VALUES(order_id),
    product_id = VALUES(product_id),
    quantity = VALUES(quantity),
    unit_price = VALUES(unit_price),
    discount_percent = VALUES(discount_percent);

INSERT INTO warehouse.inventory_snapshots (snapshot_id, product_id, snapshot_at, on_hand_qty, reserved_qty) VALUES
    (5001, 2001, '2026-02-01 00:00:00', 320, 14),
    (5002, 2002, '2026-02-01 00:00:00', 210, 8),
    (5003, 2003, '2026-02-01 00:00:00', 540, 21),
    (5004, 2004, '2026-02-01 00:00:00', 260, 11),
    (5005, 2005, '2026-02-01 00:00:00', 610, 25),
    (5006, 2006, '2026-02-01 00:00:00', 92, 6),
    (5007, 2001, '2026-02-06 00:00:00', 301, 22),
    (5008, 2002, '2026-02-06 00:00:00', 205, 11),
    (5009, 2003, '2026-02-06 00:00:00', 503, 24),
    (5010, 2004, '2026-02-06 00:00:00', 244, 10),
    (5011, 2005, '2026-02-06 00:00:00', 579, 31),
    (5012, 2006, '2026-02-06 00:00:00', 87, 7)
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id),
    snapshot_at = VALUES(snapshot_at),
    on_hand_qty = VALUES(on_hand_qty),
    reserved_qty = VALUES(reserved_qty);

INSERT INTO warehouse.dim_date (date_key, fiscal_week, fiscal_month, fiscal_quarter, is_weekend) VALUES
    ('2026-02-01', '2026-W05', '2026-02', '2026-Q1', TRUE),
    ('2026-02-02', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-03', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-04', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-05', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-06', '2026-W06', '2026-02', '2026-Q1', FALSE),
    ('2026-02-07', '2026-W06', '2026-02', '2026-Q1', TRUE)
ON DUPLICATE KEY UPDATE
    fiscal_week = VALUES(fiscal_week),
    fiscal_month = VALUES(fiscal_month),
    fiscal_quarter = VALUES(fiscal_quarter),
    is_weekend = VALUES(is_weekend);

INSERT INTO warehouse.fact_daily_sales (
    date_key,
    gross_revenue,
    net_revenue,
    order_count,
    item_count,
    distinct_customers,
    avg_order_value
)
SELECT
    DATE(o.placed_at) AS date_key,
    ROUND(SUM(oi.quantity * oi.unit_price), 2) AS gross_revenue,
    ROUND(SUM(oi.quantity * oi.unit_price * (1 - (oi.discount_percent / 100))), 2) AS net_revenue,
    COUNT(DISTINCT o.order_id) AS order_count,
    SUM(oi.quantity) AS item_count,
    COUNT(DISTINCT o.customer_id) AS distinct_customers,
    ROUND(
        SUM(oi.quantity * oi.unit_price * (1 - (oi.discount_percent / 100))) /
            NULLIF(COUNT(DISTINCT o.order_id), 0),
        2
    ) AS avg_order_value
FROM warehouse.orders o
JOIN warehouse.order_items oi
    ON oi.order_id = o.order_id
WHERE o.order_status IN ('PAID', 'SHIPPED', 'DELIVERED')
GROUP BY DATE(o.placed_at)
ON DUPLICATE KEY UPDATE
    gross_revenue = VALUES(gross_revenue),
    net_revenue = VALUES(net_revenue),
    order_count = VALUES(order_count),
    item_count = VALUES(item_count),
    distinct_customers = VALUES(distinct_customers),
    avg_order_value = VALUES(avg_order_value);

CREATE OR REPLACE VIEW warehouse.v_customer_ltv AS
SELECT
    c.customer_id,
    c.customer_name,
    c.customer_segment,
    c.region,
    COUNT(DISTINCT o.order_id) AS total_orders,
    ROUND(SUM(oi.quantity * oi.unit_price * (1 - (oi.discount_percent / 100))), 2) AS lifetime_value
FROM warehouse.customers c
JOIN warehouse.orders o
    ON o.customer_id = c.customer_id
JOIN warehouse.order_items oi
    ON oi.order_id = o.order_id
WHERE o.order_status IN ('PAID', 'SHIPPED', 'DELIVERED')
GROUP BY c.customer_id, c.customer_name, c.customer_segment, c.region;

CREATE USER IF NOT EXISTS 'readonly'@'%' IDENTIFIED BY 'readonly';
GRANT SELECT, SHOW VIEW ON warehouse.* TO 'readonly'@'%';
FLUSH PRIVILEGES;
