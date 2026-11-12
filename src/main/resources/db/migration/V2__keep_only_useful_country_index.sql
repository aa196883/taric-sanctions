-- The search first selects HS6 measures, then probes (measure_id, country_code).
-- Its primary key already covers that lookup; the reverse index duplicates millions of entries.
DROP INDEX measure_countries_country;
