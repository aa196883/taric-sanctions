ALTER TABLE measures
    DROP COLUMN action,
    ADD COLUMN measure_type_code VARCHAR(3) NOT NULL,
    ADD COLUMN measure_type TEXT NOT NULL;