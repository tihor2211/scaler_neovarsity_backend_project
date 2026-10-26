-- Demo data: three Bengaluru dark stores, six categories and an everyday grocery catalogue.
-- Used for local runs, Postman demos and the AWS deployment. Tests create their own data.

INSERT INTO stores (id, name, address, latitude, longitude) VALUES
    (1, 'DashCart BTM Layout',  '16th Main Road, BTM 2nd Stage, Bengaluru',   12.916600, 77.610100),
    (2, 'DashCart Koramangala', '80 Feet Road, Koramangala 4th Block, Bengaluru', 12.935200, 77.624500),
    (3, 'DashCart Indiranagar', '100 Feet Road, HAL 2nd Stage, Bengaluru',    12.978400, 77.640800);

INSERT INTO serviceable_pincodes (pincode, store_id) VALUES
    ('560076', 1), ('560068', 1), ('560029', 1),
    ('560034', 2), ('560095', 2), ('560047', 2),
    ('560038', 3), ('560008', 3), ('560075', 3);

INSERT INTO categories (id, name) VALUES
    (1, 'Fruits & Vegetables'),
    (2, 'Dairy, Bread & Eggs'),
    (3, 'Atta, Rice & Dal'),
    (4, 'Snacks & Munchies'),
    (5, 'Cold Drinks & Juices'),
    (6, 'Personal Care');

INSERT INTO products (id, category_id, name, description, unit, mrp, price) VALUES
    (1,  1, 'Banana Robusta',          'Ripe, sweet bananas',                    '6 pcs',   60.00,  49.00),
    (2,  1, 'Apple Shimla',            'Crisp red apples from Himachal',        '4 pcs',  180.00, 149.00),
    (3,  1, 'Onion',                   'Fresh red onions',                       '1 kg',    55.00,  42.00),
    (4,  1, 'Tomato Hybrid',           'Firm, juicy tomatoes',                   '500 g',   40.00,  29.00),
    (5,  1, 'Potato',                  'Everyday cooking potatoes',              '1 kg',    45.00,  36.00),
    (6,  1, 'Coriander Leaves',        'Fresh bunch',                            '100 g',   20.00,  15.00),
    (7,  2, 'Toned Milk',              'Pasteurised toned milk',                 '500 ml',  28.00,  28.00),
    (8,  2, 'Curd',                    'Thick set curd',                         '400 g',   45.00,  42.00),
    (9,  2, 'Brown Bread',             'Whole wheat sandwich bread',             '400 g',   55.00,  50.00),
    (10, 2, 'Farm Eggs',               'Brown eggs',                             '6 pcs',   72.00,  66.00),
    (11, 2, 'Paneer',                  'Fresh malai paneer',                     '200 g',   95.00,  89.00),
    (12, 2, 'Salted Butter',           'Table butter',                           '100 g',   60.00,  58.00),
    (13, 3, 'Whole Wheat Atta',        'Stone-ground chakki atta',               '5 kg',   295.00, 249.00),
    (14, 3, 'Sona Masoori Rice',       'Aged raw rice',                          '5 kg',   420.00, 365.00),
    (15, 3, 'Toor Dal',                'Unpolished toor dal',                    '1 kg',   180.00, 159.00),
    (16, 3, 'Moong Dal',               'Yellow split moong',                     '500 g',   85.00,  74.00),
    (17, 3, 'Sunflower Oil',           'Refined sunflower oil',                  '1 L',    175.00, 152.00),
    (18, 4, 'Salted Potato Chips',     'Classic salted chips',                   '90 g',    50.00,  45.00),
    (19, 4, 'Aloo Bhujia',             'Crunchy namkeen',                        '400 g',  110.00,  95.00),
    (20, 4, 'Digestive Biscuits',      'Whole wheat digestive biscuits',         '250 g',   60.00,  54.00),
    (21, 4, 'Dark Chocolate',          '55% cocoa bar',                          '100 g',  120.00, 110.00),
    (22, 4, 'Roasted Peanuts',         'Salted roasted peanuts',                 '200 g',   70.00,  59.00),
    (23, 5, 'Cola',                    'Chilled soft drink',                     '750 ml',  45.00,  40.00),
    (24, 5, 'Mango Drink',             'Mango fruit drink',                      '1.2 L',   75.00,  68.00),
    (25, 5, 'Tender Coconut Water',    'No added sugar',                         '200 ml',  50.00,  45.00),
    (26, 5, 'Packaged Drinking Water', 'Mineral-added water',                    '1 L',     20.00,  20.00),
    (27, 6, 'Bathing Soap',            'Sandal bathing bar, pack of 4',          '4 x 100 g', 180.00, 155.00),
    (28, 6, 'Toothpaste',              'Cavity protection toothpaste',           '150 g',  110.00,  99.00),
    (29, 6, 'Shampoo',                 'Anti-dandruff shampoo',                  '340 ml', 375.00, 329.00),
    (30, 6, 'Hand Wash Refill',        'Germ protection hand wash',              '750 ml', 149.00, 125.00);

-- Every store stocks every product, with different quantities; a few are sold out.
INSERT INTO store_inventory (store_id, product_id, available_qty)
SELECT s.id,
       p.id,
       CASE
           WHEN (s.id = 1 AND p.id IN (6, 21)) OR (s.id = 2 AND p.id = 11) OR (s.id = 3 AND p.id IN (2, 25)) THEN 0
           ELSE 20 + MOD(s.id * 37 + p.id * 11, 80)
       END
FROM stores s
CROSS JOIN products p;
