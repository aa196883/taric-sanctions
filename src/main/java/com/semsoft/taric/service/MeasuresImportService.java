package com.semsoft.taric.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeasuresImportService {

    // Only categories directly identifiable from TARIC measure types.
    private static final Map<String, String> TYPE_CATEGORIES = Map.of(
            "478", "dual_use",
            "738", "military_list",
            "705", "torture_repression",
            "706", "torture_repression",
            "708", "torture_repression"
    );

    private static final Set<String> COUNTRIES = new HashSet<>(
            Arrays.asList(Locale.getISOCountries())
    );

    static {
        COUNTRIES.add("XK"); // Kosovo
    }

    private final JdbcTemplate jdbc;

    public MeasuresImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Measure(
            long id,
            String goodsCode,
            String direction,
            String typeCode,
            String type,
            String legalReference,
            String country
    ) {}

    @Transactional(rollbackFor = Exception.class)
    public boolean importFiles(Path importFile, Path exportFile)
            throws IOException {

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measures",
                Integer.class
        );

        if (count != null && count > 0) {
            return false;
        }

        // Load existing goods codes to validate foreign keys.
        Set<String> goods = new HashSet<>(
                jdbc.queryForList(
                        "SELECT code FROM goods",
                        String.class
                )
        );

        if (goods.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot import measures: goods table is empty"
            );
        }

        List<Measure> measures = new ArrayList<>();

        readFile(importFile, "import", goods, measures);
        readFile(exportFile, "export", goods, measures);

        if (measures.isEmpty()) {
            throw new IllegalStateException(
                    "No measures found in the supplied files"
            );
        }

        // Insert measures.
        jdbc.batchUpdate(
                """
                INSERT INTO measures (
                    id,
                    goods_code,
                    direction,
                    measure_type_code,
                    measure_type,
                    legal_reference
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                measures,
                500,
                (ps, m) -> {
                    ps.setLong(1, m.id());
                    ps.setString(2, m.goodsCode());
                    ps.setString(3, m.direction());
                    ps.setString(4, m.typeCode());
                    ps.setString(5, m.type());
                    ps.setString(6, m.legalReference());
                }
        );

        // Insert direct country associations.
        jdbc.batchUpdate(
                """
                INSERT INTO measure_countries (
                    measure_id,
                    country_code
                )
                VALUES (?, ?)
                """,
                measures,
                500,
                (ps, m) -> {
                    ps.setLong(1, m.id());
                    ps.setString(2, m.country());
                }
        );

        // Insert categories derived from measure types.
        List<Measure> categorized = measures.stream()
                .filter(m -> TYPE_CATEGORIES.containsKey(m.typeCode()))
                .toList();

        jdbc.batchUpdate(
                """
                INSERT INTO measure_categories (
                    measure_id,
                    category,
                    source
                )
                VALUES (?, ?, 'measure_type')
                """,
                categorized,
                500,
                (ps, m) -> {
                    ps.setLong(1, m.id());
                    ps.setString(2, TYPE_CATEGORIES.get(m.typeCode()));
                }
        );

        // Explicit IDs were used: synchronize the PostgreSQL identity sequence.
        jdbc.execute(
                """
                SELECT setval(
                    pg_get_serial_sequence('measures', 'id'),
                    (SELECT MAX(id) FROM measures),
                    true
                )
                """
        );

        System.out.println("=== TARIC measures import ===");
        System.out.println("Measures inserted: " + measures.size());
        System.out.println("Countries inserted: " + measures.size());
        System.out.println("Categories inserted: " + categorized.size());

        return true;
    }

    private void readFile(
            Path file,
            String direction,
            Set<String> goods,
            List<Measure> measures
    ) throws IOException {

        DataFormatter formatter = new DataFormatter();

        int total = 0;
        int imported = 0;
        int skippedGeography = 0;
        int skippedGoods = 0;

        try (var input = Files.newInputStream(file);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                var row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                total++;

                String country = formatter.formatCellValue(
                        row.getCell(10)).trim();

                if (!COUNTRIES.contains(country)) {
                    skippedGeography++;
                    continue;
                }

                String goodsCode = formatter.formatCellValue(
                        row.getCell(0)).trim();

                if (!goods.contains(goodsCode)) {
                    skippedGoods++;
                    continue;
                }

                String typeCode = formatter.formatCellValue(
                        row.getCell(11)).trim();

                String type = formatter.formatCellValue(
                        row.getCell(7)).trim();

                String legalReference = formatter.formatCellValue(
                        row.getCell(8)).trim();

                if (typeCode.isEmpty()
                        || type.isEmpty()
                        || legalReference.isEmpty()) {

                    throw new IllegalArgumentException(
                            "Missing measure information at row "
                                    + (i + 1) + " in " + file
                    );
                }

                measures.add(new Measure(
                        measures.size() + 1L,
                        goodsCode,
                        direction,
                        typeCode,
                        type,
                        legalReference,
                        country
                ));

                imported++;
            }
        }

        System.out.println("=== " + direction.toUpperCase() + " ===");
        System.out.println("Source rows: " + total);
        System.out.println("Measures selected: " + imported);
        System.out.println("Skipped geographical groups: " + skippedGeography);
        System.out.println("Skipped unknown goods: " + skippedGoods);
    }
}