package com.semsoft.taric.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

@Repository
public class TaricStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TaricStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    void lock() { jdbc.execute("SELECT pg_advisory_xact_lock(8274102026)"); }
    LocalDate asOf() { return jdbc.queryForObject("SELECT as_of FROM database_state WHERE singleton=1", LocalDate.class); }
    String rulesVersion() { return jdbc.queryForObject("SELECT rules_version FROM taric_source.manifest WHERE singleton=1", String.class); }
    Source load() {
        var source = new Source();
        jdbc.query("SELECT dataset, cells::text FROM taric_source.rows ORDER BY dataset, source_key", rs -> {
            try {
                source.publish(Source.Table.valueOf(rs.getString(1)), json.readValue(rs.getString(2), new TypeReference<List<String>>() {}));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        return source;
    }
    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
    }

    /** Build and validate isolated temporary tables, then publish source + business in one MVCC commit. */
    void publish(Source source, Consolidator.Result result, LocalDate asOf, String snapshot,
                 String provenance, Map<String, String> checksums, Set<String> impacted) {
        var tables = List.of("goods", "measures", "measure_countries", "measure_categories", "footnotes", "measure_footnotes");
        for (var table : tables) jdbc.execute("CREATE TEMP TABLE next_" + table + " (LIKE " + table + " INCLUDING ALL) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE next_source (LIKE taric_source.rows INCLUDING ALL) ON COMMIT DROP");
        batch("INSERT INTO next_source VALUES (?, ?, ?::jsonb)", add -> source.tables.forEach((table, rows) ->
            rows.forEach((key, row) -> add.accept(new Object[]{table.name(), key, json(row)}))));
        batch("INSERT INTO next_goods VALUES (?, ?)", add -> result.goods().forEach((code, text) -> add.accept(new Object[]{code, text})));
        batch("INSERT INTO next_footnotes VALUES (?, ?)", add -> result.footnotes().forEach((code, text) -> add.accept(new Object[]{code, text})));
        // Reusing IDs of unaffected measures is unnecessary on a full rebuild; partial replacement allocates above MAX(id).
        long first = impacted == null ? 1 : jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1 FROM measures", Long.class);
        var selected = result.measures().stream().filter(m -> impacted == null || impacted.contains(m.signature().hs6())).toList();
        batch("INSERT INTO next_measures VALUES (?, ?, ?, ?, ?, ?, ?, ?)", add -> {
            long id = first;
            for (var m : selected) {
                var s = m.signature();
                add.accept(new Object[]{id++, s.hs6(), s.direction(), s.action(), s.legalReference(), m.legalUrl(), s.scopeKey(), s.geographyKey()});
            }
        });
        batch("INSERT INTO next_measure_countries VALUES (?, ?)", add -> {
            long id = first;
            for (var m : selected) { for (var country : m.countries()) add.accept(new Object[]{id, country}); id++; }
        });
        batch("INSERT INTO next_measure_categories VALUES (?, ?, ?)", add -> {
            long id = first;
            for (var m : selected) { for (var category : m.categories().entrySet()) add.accept(new Object[]{id, category.getKey(), category.getValue()}); id++; }
        });
        batch("INSERT INTO next_measure_footnotes VALUES (?, ?)", add -> {
            long id = first;
            for (var m : selected) { for (var code : m.footnotes()) add.accept(new Object[]{id, code}); id++; }
        });
        // The staging tables have uniqueness/check constraints; validate their cross-table relations before touching live rows.
        for (String query : List.of(
            "SELECT count(*) FROM next_measures m LEFT JOIN next_goods g ON g.hs6=m.hs6 WHERE g.hs6 IS NULL",
            "SELECT count(*) FROM next_measure_footnotes mf LEFT JOIN next_footnotes f ON f.code=mf.footnote_code WHERE f.code IS NULL",
            "SELECT count(*) FROM next_measures m WHERE NOT EXISTS (SELECT 1 FROM next_measure_countries c WHERE c.measure_id=m.id)")) {
            if (jdbc.queryForObject(query, Long.class) != 0) throw new IllegalStateException("Invalid consolidation: " + query);
        }
        if (impacted == null) jdbc.update("DELETE FROM measures");
        else batch("DELETE FROM measures WHERE hs6=?", add -> impacted.forEach(hs -> add.accept(new Object[]{hs})));
        jdbc.update("INSERT INTO goods SELECT * FROM next_goods ON CONFLICT (hs6) DO UPDATE SET description=excluded.description");
        jdbc.update("INSERT INTO footnotes SELECT * FROM next_footnotes ON CONFLICT (code) DO UPDATE SET text=excluded.text");
        for (String table : List.of("measures", "measure_countries", "measure_categories", "measure_footnotes"))
            jdbc.update("INSERT INTO " + table + " SELECT * FROM next_" + table);
        jdbc.update("DELETE FROM goods g WHERE NOT EXISTS (SELECT 1 FROM next_goods n WHERE n.hs6=g.hs6)");
        jdbc.update("DELETE FROM footnotes f WHERE NOT EXISTS (SELECT 1 FROM measure_footnotes mf WHERE mf.footnote_code=f.code)");
        jdbc.execute("ANALYZE measures, measure_countries, measure_categories, measure_footnotes, goods, footnotes");
        jdbc.update("DELETE FROM taric_source.rows");
        jdbc.update("INSERT INTO taric_source.rows SELECT * FROM next_source");
        jdbc.update("INSERT INTO taric_source.manifest VALUES (1, ?, ?::jsonb, ?) ON CONFLICT (singleton) DO UPDATE SET provenance=excluded.provenance, checksums=excluded.checksums, rules_version=excluded.rules_version",
            provenance, json(checksums), Rules.VERSION);
        jdbc.update("INSERT INTO database_state VALUES (1, ?, ?) ON CONFLICT (singleton) DO UPDATE SET as_of=excluded.as_of, source_snapshot=excluded.source_snapshot", asOf, snapshot);
    }
    private void batch(String sql, Consumer<Consumer<Object[]>> producer) {
        var buffer = new ArrayList<Object[]>(1000);
        producer.accept(row -> {
            buffer.add(row);
            if (buffer.size() == 1000) { jdbc.batchUpdate(sql, buffer); buffer.clear(); }
        });
        if (!buffer.isEmpty()) jdbc.batchUpdate(sql, buffer);
    }
}
