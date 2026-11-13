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

public class GeographyResolver {

    private final Map<String, Set<String>> groups = new HashMap<>();
    private final Map<String, String> abbreviations = new HashMap<>();
    private final Set<String> countries = new HashSet<>();

    public GeographyResolver(Path file) throws IOException {

        DataFormatter formatter = new DataFormatter();

        try (var input = Files.newInputStream(file);
             var workbook = new XSSFWorkbook(input)) {

            var sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {

                var row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String group = formatter.formatCellValue(row.getCell(0)).trim();
                String abbreviation = formatter.formatCellValue(row.getCell(3)).trim();
                String member = formatter.formatCellValue(row.getCell(5)).trim();

                if (group.isEmpty() || !member.matches("[A-Z]{2}")) {
                    throw new IllegalArgumentException(
                            "Invalid geographical composition at row " + (i + 1)
                    );
                }

                groups.computeIfAbsent(group, k -> new HashSet<>())
                      .add(member);

                countries.add(member);

                if (abbreviation.matches("[A-Z]{2}")) {
                    String previous = abbreviations.putIfAbsent(
                            abbreviation, group
                    );

                    if (previous != null && !previous.equals(group)) {
                        throw new IllegalArgumentException(
                                "Ambiguous geographical abbreviation: "
                                        + abbreviation
                        );
                    }
                }
            }
        }
    }

    public Set<String> resolve(String code) {
        return resolve(code, new HashSet<>());
    }

    private Set<String> resolve(String code, Set<String> visited) {

        String group = groups.containsKey(code)
                ? code
                : abbreviations.get(code);

        if (group == null) {
            if (countries.contains(code)) {
                return Set.of(code);
            }

            throw new IllegalArgumentException(
                    "Unknown geographical code: " + code
            );
        }

        if (!visited.add(group)) {
            throw new IllegalStateException(
                    "Circular geographical group: " + group
            );
        }

        Set<String> result = new HashSet<>();

        for (String member : groups.get(group)) {
            result.addAll(resolve(member, visited));
        }

        visited.remove(group);

        return result;
    }
}