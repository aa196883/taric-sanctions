package com.semsoft.taric;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
public class MeasureSearch {
    public record Footnote(String code, String text) {}
    public record Measure(long id, String hs6, String direction, String action, String legalReference,
                          String legalUrl, List<String> categories, List<Footnote> footnotes) {}
    private final JdbcTemplate jdbc;
    public MeasureSearch(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Input direction is EU-based; returned/stored direction is the partner country's perspective. */
    @GetMapping("/measures")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Measure> find(@RequestParam String hs6, @RequestParam String direction, @RequestParam String country) {
        if (!hs6.matches("[0-9]{6}") || !Set.of("import", "export").contains(direction) || !country.matches("[A-Z]{2}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected HS6, EU direction import/export and uppercase country code");
        String storedDirection = direction.equals("export") ? "import" : "export";
        String normalizedCountry = country.equals("XS") ? "RS" : country;
        return jdbc.query("""
            SELECT m.* FROM measures m JOIN measure_countries mc ON mc.measure_id=m.id
            WHERE m.hs6=? AND m.direction=? AND mc.country_code=? ORDER BY m.id
            """, (rs, row) -> {
                long id = rs.getLong("id");
                var categories = jdbc.queryForList("SELECT category FROM measure_categories WHERE measure_id=? ORDER BY category", String.class, id);
                var notes = jdbc.query("SELECT f.code, f.text FROM footnotes f JOIN measure_footnotes mf ON mf.footnote_code=f.code WHERE mf.measure_id=? ORDER BY f.code",
                    (note, n) -> new Footnote(note.getString(1), note.getString(2)), id);
                return new Measure(id, rs.getString("hs6"), rs.getString("direction"), rs.getString("action"),
                    rs.getString("legal_reference"), rs.getString("legal_url"), categories, notes);
            }, hs6, storedDirection, normalizedCountry);
    }
}
