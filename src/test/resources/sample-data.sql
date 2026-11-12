-- Goods
INSERT INTO goods (code, description) VALUES
('2914110000', 'Acetone - subdivision 1'),
('2914111000', 'Acetone - subdivision 2'),
('2844100000', 'Radioactive materials');

-- Measures
INSERT INTO measures
    (id, goods_code, direction, action, legal_reference, legal_url)
VALUES
    (9001, '2914110000', 'export', 'authorization',
     'Regulation 0111/05', NULL),

    (9002, '2914111000', 'export', 'authorization',
     'Regulation 0111/05', NULL),

    (9003, '2844100000', 'export', 'authorization',
     'Example Dual-Use Regulation', NULL);

-- Countries
INSERT INTO measure_countries (measure_id, country_code) VALUES
(9001, 'IR'),
(9001, 'RU'),
(9002, 'IR'),
(9003, 'RU');

-- Categories
INSERT INTO measure_categories (measure_id, category, source) VALUES
(9001, 'drug_precursor', 'manual_rule'),
(9002, 'drug_precursor', 'manual_rule'),
(9003, 'dual_use', 'measure_type');

-- Footnotes
INSERT INTO footnotes (code, text) VALUES
('CD259', 'Example footnote concerning drug precursors'),
('TEST1', 'Additional example footnote');

-- Footnote associations
INSERT INTO measure_footnotes (measure_id, footnote_code) VALUES
(9001, 'CD259'),
(9001, 'TEST1'),
(9002, 'CD259');