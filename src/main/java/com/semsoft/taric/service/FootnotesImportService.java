package com.semsoft.taric.service;

import com.semsoft.taric.geography.MeasureExclusions.MeasureKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FootnotesImportService {

    private final JdbcTemplate jdbc;

    public FootnotesImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Footnote(String code, String text) {}

    private record Association(long measureId, String footnoteCode) {}

    @Transactional(rollbackFor = Exception.class)
    public boolean importFiles(
            Path descriptionsFile,
            Path associationsFile,
            Map<MeasureKey, List<Long>> measureIds
    ) throws IOException {

        Boolean alreadyImported = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM footnotes)",
                Boolean.class
        );

        if (Boolean.TRUE.equals(alreadyImported)) {
            return false;
        }

        if (measureIds.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot import footnotes: no imported measures"
            );
        }

        DataFormatter formatter = new DataFormatter();

        // 1. Load English descriptions.
        Map<String, String> descriptions = new HashMap<>();

        try (var input = Files.newInputStream(descriptionsFile);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                Row row = sheet.getRow(i);
                if (row == null) continue;

                if (!cell(row, 1, formatter).equals("EN")) {
                    continue;
                }

                String code = cell(row, 0, formatter);
                String text = cell(row, 2, formatter);

                if (code.isEmpty() || text.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Invalid footnote at row " + (i + 1)
                    );
                }

                String previous = descriptions.putIfAbsent(code, text);

                if (previous != null && !previous.equals(text)) {
                    throw new IllegalArgumentException(
                            "Conflicting descriptions for footnote " + code
                    );
                }
            }
        }

        if (descriptions.isEmpty()) {
            throw new IllegalStateException("No English footnotes found");
        }

        List<Footnote> footnotes = descriptions.entrySet().stream()
                .map(e -> new Footnote(e.getKey(), e.getValue()))
                .toList();

        jdbc.batchUpdate(
                "INSERT INTO footnotes (code, text) VALUES (?, ?)",
                footnotes,
                500,
                (ps, f) -> {
                    ps.setString(1, f.code());
                    ps.setString(2, f.text());
                }
        );

        // 2. Match source footnotes with imported measures.
        Set<Association> associations = new HashSet<>();

        int sourceRows = 0;
        int unmatchedRows = 0;

        try (var input = Files.newInputStream(associationsFile);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                Row row = sheet.getRow(i);
                if (row == null) continue;

                sourceRows++;

                MeasureKey key = new MeasureKey(
                        cell(row, 0, formatter), // Goods code
                        cell(row, 1, formatter), // Add code
                        cell(row, 2, formatter), // Order No.
                        cell(row, 4, formatter), // Start date
                        cell(row, 3, formatter), // Origin code
                        cell(row, 6, formatter)  // Meas. type code
                );

                String footnoteCode = cell(row, 9, formatter);

                if (!descriptions.containsKey(footnoteCode)) {
                    throw new IllegalArgumentException(
                            "Missing description for footnote "
                                    + footnoteCode + " at row " + (i + 1)
                    );
                }

                List<Long> ids = measureIds.get(key);

                if (ids == null) {
                    unmatchedRows++;
                    continue;
                }

                for (long id : ids) {
                    associations.add(new Association(id, footnoteCode));
                }
            }
        }

        // 3. Insert associations.
        jdbc.batchUpdate(
                """
                INSERT INTO measure_footnotes (measure_id, footnote_code)
                VALUES (?, ?)
                """,
                new ArrayList<>(associations),
                500,
                (ps, a) -> {
                    ps.setLong(1, a.measureId());
                    ps.setString(2, a.footnoteCode());
                }
        );

        System.out.println("=== TARIC FOOTNOTES ===");
        System.out.println("Descriptions inserted: " + footnotes.size());
        System.out.println("Source association rows: " + sourceRows);
        System.out.println("Associations inserted: " + associations.size());
        System.out.println("Unmatched source rows: " + unmatchedRows);

        return true;
    }

    private static String cell(Row row, int column, DataFormatter formatter) {
        return formatter.formatCellValue(row.getCell(column)).trim();
    }
}