CREATE SCHEMA taric_source;
CREATE TABLE taric_source.rows (
    dataset TEXT NOT NULL,
    source_key TEXT NOT NULL,
    cells JSONB NOT NULL,
    PRIMARY KEY (dataset, source_key)
);
CREATE TABLE taric_source.manifest (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    provenance TEXT NOT NULL,
    checksums JSONB NOT NULL,
    rules_version TEXT NOT NULL
);
CREATE TABLE goods (
    hs6 TEXT PRIMARY KEY CHECK (hs6 ~ '^[0-9]{6}$'),
    description TEXT NOT NULL
);
CREATE TABLE measures (
    id BIGINT PRIMARY KEY,
    hs6 TEXT NOT NULL REFERENCES goods,
    direction TEXT NOT NULL CHECK (direction IN ('import', 'export')),
    action TEXT NOT NULL CHECK (action IN ('authorization', 'prohibition', 'control', 'restriction', 'declaration')),
    legal_reference TEXT NOT NULL,
    legal_url TEXT,
    scope_key TEXT NOT NULL,
    geography_key TEXT NOT NULL,
    UNIQUE (hs6, direction, action, legal_reference, scope_key, geography_key)
);
CREATE TABLE measure_countries (
    measure_id BIGINT NOT NULL REFERENCES measures ON DELETE CASCADE,
    country_code TEXT NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    PRIMARY KEY (measure_id, country_code)
);
CREATE INDEX measure_countries_country ON measure_countries (country_code, measure_id);
CREATE TABLE measure_categories (
    measure_id BIGINT NOT NULL REFERENCES measures ON DELETE CASCADE,
    category TEXT NOT NULL CHECK (category IN ('dual_use', 'military_list', 'drug_precursor', 'sanction',
        'torture_repression', 'firearms', 'chemical_control', 'restricted_goods_or_technology')),
    source TEXT NOT NULL CHECK (source IN ('measure_type', 'footnote', 'legal_reference', 'manual_rule')),
    PRIMARY KEY (measure_id, category)
);
CREATE TABLE footnotes (code TEXT PRIMARY KEY, text TEXT NOT NULL);
CREATE TABLE measure_footnotes (
    measure_id BIGINT NOT NULL REFERENCES measures ON DELETE CASCADE,
    footnote_code TEXT NOT NULL REFERENCES footnotes,
    PRIMARY KEY (measure_id, footnote_code)
);
CREATE INDEX measure_footnotes_code ON measure_footnotes (footnote_code, measure_id);
CREATE TABLE database_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    as_of DATE NOT NULL,
    source_snapshot TEXT NOT NULL
);
