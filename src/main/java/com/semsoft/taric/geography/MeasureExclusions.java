package com.semsoft.taric.geography;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class MeasureExclusions {

    public record MeasureKey(
            String goodsCode,
            String additionalCode,
            String orderNumber,
            String startDate,
            String geographicalCode,
            String measureTypeCode
    ) {}

    private final Map<MeasureKey, Set<String>> exclusions = new HashMap<>();

    public MeasureExclusions(Path file) throws IOException {

        DataFormatter formatter = new DataFormatter();

        try (var input = Files.newInputStream(file);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                var row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                MeasureKey key = new MeasureKey(
                        formatter.formatCellValue(row.getCell(0)).trim(),
                        formatter.formatCellValue(row.getCell(1)).trim(),
                        formatter.formatCellValue(row.getCell(2)).trim(),
                        formatter.formatCellValue(row.getCell(3)).trim(),
                        formatter.formatCellValue(row.getCell(8)).trim(),
                        formatter.formatCellValue(row.getCell(9)).trim()
                );

                String country = formatter.formatCellValue(
                        row.getCell(10)).trim();

                if (!country.matches("[A-Z]{2}")) {
                    throw new IllegalArgumentException(
                            "Invalid excluded country at row " + (i + 1)
                    );
                }

                exclusions.computeIfAbsent(key, k -> new HashSet<>())
                          .add(country);
            }
        }
    }

    public Set<String> getExcludedCountries(MeasureKey key) {
        return exclusions.getOrDefault(key, Set.of());
    }

    public int size() {
        return exclusions.size();
    }
}