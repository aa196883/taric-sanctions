package com.semsoft.taric.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Service
public class GoodsImportService {

    private final JdbcTemplate jdbc;

    public GoodsImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Good(String code, String description, int suffix) {}

    @Transactional(rollbackFor = Exception.class)
    public boolean importFile(Path file) throws IOException {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM goods",
                Integer.class
        );

        if (count != null && count > 0) {
            return false;
        }

        Map<String, Good> goods = new HashMap<>();
        DataFormatter formatter = new DataFormatter();

        try (var input = Files.newInputStream(file);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            // Skip header
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                var row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String rawCode = formatter.formatCellValue(row.getCell(0)).trim();
                String description = formatter.formatCellValue(row.getCell(6)).trim();

                if (!rawCode.matches("\\d{10} \\d{2}")) {
                    throw new IllegalArgumentException(
                            "Invalid goods code at row " + (i + 1) + ": " + rawCode);
                }

                String code = rawCode.substring(0, 10);
                int suffix = Integer.parseInt(rawCode.substring(11));

                Good previous = goods.get(code);

                if (previous == null
                        || (suffix == 80 && previous.suffix() != 80)
                        || (previous.suffix() != 80 && suffix > previous.suffix())) {

                    goods.put(code, new Good(code, description, suffix));
                }
            }
        }

        int exceptions = (int) goods.values().stream()
                .filter(g -> g.suffix() != 80)
                .count();

        if (exceptions > 0) {
            System.out.println(
                    "Goods without suffix 80: " + exceptions);
        }

        jdbc.batchUpdate(
                """
                INSERT INTO goods (code, description)
                VALUES (?, ?)
                ON CONFLICT (code)
                DO UPDATE SET description = EXCLUDED.description
                """,
                goods.values(),
                500,
                (ps, good) -> {
                    ps.setString(1, good.code());
                    ps.setString(2, good.description());
                }
        );

        return true;
    }
}