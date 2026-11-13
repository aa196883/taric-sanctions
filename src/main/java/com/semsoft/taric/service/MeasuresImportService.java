package com.semsoft.taric.service;

import com.semsoft.taric.geography.GeographyResolver;
import com.semsoft.taric.geography.MeasureExclusions;
import com.semsoft.taric.geography.MeasureExclusions.MeasureKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeasuresImportService {

    private static final Map<String, String> TYPE_CATEGORIES = Map.of(
            "478", "dual_use",
            "738", "military_list",
            "705", "torture_repression",
            "706", "torture_repression",
            "708", "torture_repression"
    );

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
            Set<String> countries
    ) {}

    private record CountryAssociation(long measureId, String country) {}

    @Transactional(rollbackFor = Exception.class)
    public boolean importFiles(
            Path importFile,
            Path exportFile,
            Path geographyFile,
            Path exclusionsFile
    ) throws IOException {

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measures",
                Integer.class
        );

        if (count != null && count > 0) {
            return false;
        }

        Set<String> goods = new HashSet<>(
                jdbc.queryForList("SELECT code FROM goods", String.class)
        );

        if (goods.isEmpty()) {
            throw new IllegalStateException("Goods table is empty");
        }

        long phaseStart = System.nanoTime();
        GeographyResolver geography = new GeographyResolver(geographyFile);
        System.out.printf("PROFILE geography: %.3f s%n", (System.nanoTime() - phaseStart) / 1e9);
        phaseStart = System.nanoTime();
        MeasureExclusions exclusions = new MeasureExclusions(exclusionsFile);

        System.out.printf("PROFILE exclusions: %.3f s%n", (System.nanoTime() - phaseStart) / 1e9);
        long nextId = 1;

        nextId = readFile(
                importFile, "import", goods, geography, exclusions, nextId
        );

        nextId = readFile(
                exportFile, "export", goods, geography, exclusions, nextId
        );

        if (nextId == 1) {
            throw new IllegalStateException("No measures imported");
        }

        // Synchronize identity sequence after explicit IDs.
        jdbc.queryForObject(
                """
                SELECT setval(
                    pg_get_serial_sequence('measures', 'id'),
                    (SELECT MAX(id) FROM measures),
                    true
                )
                """,
                Long.class
        );

        System.out.println("=== TARIC IMPORT COMPLETED ===");

        for (String table : List.of(
                "measures",
                "measure_countries",
                "measure_categories"
        )) {
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table,
                    Long.class
            );

            System.out.println(table + ": " + total);
        }

        return true;
    }

    private long readFile(
            Path file,
            String direction,
            Set<String> goods,
            GeographyResolver geography,
            MeasureExclusions exclusions,
            long nextId
    ) throws IOException {

        long fileStart = System.nanoTime();
        long[] dbNanos = new long[3];
        DataFormatter formatter = new DataFormatter();
        List<Measure> batch = new ArrayList<>();

        int total = 0;
        int imported = 0;
        int skippedGoods = 0;
        int emptyGeographies = 0;

        try (var input = Files.newInputStream(file);
             var workbook = new XSSFWorkbook(input)) {

            System.out.printf("PROFILE %s workbook load: %.3f s%n", direction, (System.nanoTime() - fileStart) / 1e9);
            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                var row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                total++;

                String goodsCode = cell(row, 0, formatter);

                if (!goods.contains(goodsCode)) {
                    skippedGoods++;
                    continue;
                }

                String typeCode = cell(row, 11, formatter);
                String type = cell(row, 7, formatter);
                String legalReference = cell(row, 8, formatter);
                String geographicalCode = cell(row, 10, formatter);

                if (typeCode.isEmpty()
                        || type.isEmpty()
                        || legalReference.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Missing measure information at row "
                                    + (i + 1) + " in " + file
                    );
                }

                MeasureKey key = new MeasureKey(
                        goodsCode,
                        cell(row, 1, formatter),
                        cell(row, 2, formatter),
                        cell(row, 3, formatter),
                        geographicalCode,
                        typeCode
                );

                // Resolve geographical group or direct country.
                Set<String> countries = new HashSet<>(
                        geography.resolve(geographicalCode)
                );

                // Apply exclusions specific to this source measure.
                countries.removeAll(
                        exclusions.getExcludedCountries(key)
                );

                if (countries.isEmpty()) {
                    emptyGeographies++;
                    continue;
                }

                batch.add(new Measure(
                        nextId++,
                        goodsCode,
                        direction,
                        typeCode,
                        type,
                        legalReference,
                        countries
                ));

                imported++;

                if (batch.size() == 250) {
                    insertBatch(batch, dbNanos);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            insertBatch(batch, dbNanos);
        }

        System.out.println("=== " + direction.toUpperCase() + " ===");
        System.out.println("Source rows: " + total);
        System.out.println("Measures inserted: " + imported);
        System.out.println("Unknown goods skipped: " + skippedGoods);
        System.out.println("Empty geographies: " + emptyGeographies);

        System.out.printf("PROFILE %s total: %.3f s; SQL measures: %.3f s; countries: %.3f s; categories: %.3f s%n", direction, (System.nanoTime() - fileStart) / 1e9, dbNanos[0] / 1e9, dbNanos[1] / 1e9, dbNanos[2] / 1e9);
        return nextId;
    }

    private static String cell(
            org.apache.poi.ss.usermodel.Row row,
            int column,
            DataFormatter formatter
    ) {
        return formatter.formatCellValue(row.getCell(column)).trim();
    }

    private void insertBatch(List<Measure> measures, long[] dbNanos) {

        long sqlStart = System.nanoTime();
        // Measures
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
                250,
                (ps, m) -> {
                    ps.setLong(1, m.id());
                    ps.setString(2, m.goodsCode());
                    ps.setString(3, m.direction());
                    ps.setString(4, m.typeCode());
                    ps.setString(5, m.type());
                    ps.setString(6, m.legalReference());
                }
        );

        dbNanos[0] += System.nanoTime() - sqlStart;
        // Countries
        List<CountryAssociation> countries = new ArrayList<>();

        for (Measure m : measures) {
            for (String country : m.countries()) {
                countries.add(new CountryAssociation(m.id(), country));
            }
        }

        sqlStart = System.nanoTime();
        jdbc.batchUpdate(
                """
                INSERT INTO measure_countries (measure_id, country_code)
                VALUES (?, ?)
                """,
                countries,
                500,
                (ps, c) -> {
                    ps.setLong(1, c.measureId());
                    ps.setString(2, c.country());
                }
        );

        dbNanos[1] += System.nanoTime() - sqlStart;
        // Categories
        List<Measure> categorized = measures.stream()
                .filter(m -> TYPE_CATEGORIES.containsKey(m.typeCode()))
                .toList();

        sqlStart = System.nanoTime();
        if (!categorized.isEmpty()) {
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
                    250,
                    (ps, m) -> {
                        ps.setLong(1, m.id());
                        ps.setString(
                                2, TYPE_CATEGORIES.get(m.typeCode())
                        );
                    }
            );
        }
        dbNanos[2] += System.nanoTime() - sqlStart;
    }
}