-- Local Vertica seed data for the Docker Compose environment.

CREATE SCHEMA IF NOT EXISTS warehouse;

CREATE TABLE IF NOT EXISTS warehouse.customers (
    customer_id INT NOT NULL,
    customer_name VARCHAR(120) NOT NULL,
    customer_segment VARCHAR(32) NOT NULL,
    region VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_customers PRIMARY KEY (customer_id)
);

CREATE TABLE IF NOT EXISTS warehouse.orders (
    order_id INT NOT NULL,
    customer_id INT NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    placed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES warehouse.customers(customer_id)
);

CREATE TABLE IF NOT EXISTS warehouse.healthcheck (
    id IDENTITY(1,1) PRIMARY KEY,
    status VARCHAR(64) NOT NULL,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO warehouse.customers (customer_id, customer_name, customer_segment, region, created_at)
SELECT 1001, 'Balin Mining Co', 'enterprise', 'north', TIMESTAMP '2025-11-12 09:14:00'
UNION ALL
SELECT 1002, 'Dori & Sons', 'mid-market', 'east', TIMESTAMP '2025-12-04 12:20:00'
UNION ALL
SELECT 1003, 'Erebor Imports', 'enterprise', 'west', TIMESTAMP '2025-12-18 16:05:00'
UNION ALL
SELECT 1004, 'Blue Mountain Crafts', 'small-business', 'south', TIMESTAMP '2026-01-02 08:10:00'
UNION ALL
SELECT 1005, 'Khazad Freight', 'mid-market', 'north', TIMESTAMP '2026-01-06 10:32:00'
UNION ALL
SELECT 1006, 'Iron Hills Outfitter', 'small-business', 'west', TIMESTAMP '2026-01-10 14:44:00';

INSERT INTO warehouse.orders (order_id, customer_id, order_status, placed_at)
SELECT 3001, 1001, 'DELIVERED', TIMESTAMP '2026-02-01 09:12:00'
UNION ALL
SELECT 3002, 1002, 'DELIVERED', TIMESTAMP '2026-02-01 13:05:00'
UNION ALL
SELECT 3003, 1003, 'SHIPPED', TIMESTAMP '2026-02-02 08:44:00'
UNION ALL
SELECT 3004, 1001, 'PAID', TIMESTAMP '2026-02-02 17:19:00'
UNION ALL
SELECT 3005, 1004, 'CANCELED', TIMESTAMP '2026-02-03 10:25:00';

INSERT INTO warehouse.healthcheck (status) VALUES ('ok');
