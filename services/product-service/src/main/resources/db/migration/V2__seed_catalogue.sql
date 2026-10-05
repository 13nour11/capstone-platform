-- Demo catalogue. Product ids 1..10 are stable: inventory-service seeds stock for the same ids.
INSERT INTO categories (name) VALUES
    ('Electronics'),
    ('Books'),
    ('Home');

INSERT INTO products (name, description, price, category_id) VALUES
    ('Wireless Mouse',        'Ergonomic 2.4 GHz mouse',              24.99, 1),
    ('Mechanical Keyboard',   'Tenkeyless, brown switches',           89.00, 1),
    ('USB-C Hub',             '7-in-1 hub with HDMI',                 39.50, 1),
    ('27-inch Monitor',       'QHD IPS panel',                       279.00, 1),
    ('Clean Code',            'Robert C. Martin',                     34.90, 2),
    ('Designing Data-Intensive Applications', 'Martin Kleppmann',     45.00, 2),
    ('Building Microservices', 'Sam Newman',                          42.00, 2),
    ('Desk Lamp',             'LED lamp with dimmer',                 29.99, 3),
    ('Office Chair',          'Mesh back, adjustable arms',          159.00, 3),
    ('Coffee Mug',            '350 ml ceramic mug',                    9.95, 3);
