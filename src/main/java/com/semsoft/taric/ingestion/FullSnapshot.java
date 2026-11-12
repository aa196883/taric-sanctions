package com.semsoft.taric.ingestion;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.ss.util.CellReference;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class FullSnapshot {
    public record Loaded(Source source, Map<String, String> checksums) {}

    public static Loaded read(Path directory) throws Exception {
        var source = new Source();
        var checksums = new TreeMap<String, String>();
        for (var table : Source.Table.values()) {
            var path = directory.resolve(table.file);
            var before = Source.hash(Files.readAllBytes(path));
            readWorkbook(path, table.headers, row -> {
                var previous = source.tables.get(table).putIfAbsent(table.key(row), row);
                if (previous != null && !previous.equals(row))
                    throw new IllegalArgumentException("Ambiguous source identity: " + table + " " + row);
            });
            if (!before.equals(Source.hash(Files.readAllBytes(path))))
                throw new IllegalStateException("Workbook changed while reading: " + path);
            checksums.put(table.file, before);
        }
        return new Loaded(source, checksums);
    }

    // SAX: never materialize a workbook's XML/POI object model in memory.
    static void readWorkbook(Path path, List<String> headers, Consumer<List<String>> consumer) throws Exception {
        try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ)) {
            var reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            var sheets = reader.getSheetsData();
            if (!sheets.hasNext()) throw new IllegalArgumentException("Empty workbook: " + path);
            try (var sheet = sheets.next()) {
                var handler = new XSSFSheetXMLHandler.SheetContentsHandler() {
                    List<String> row;
                    boolean headerSeen;
                    public void startRow(int number) { row = new ArrayList<>(Collections.nCopies(headers.size(), "")); }
                    public void cell(String ref, String value, XSSFComment comment) {
                        int column = new CellReference(ref).getCol();
                        if (column >= row.size()) throw new IllegalArgumentException("Unexpected column: " + path + " " + ref);
                        row.set(column, value.strip());
                    }
                    public void endRow(int number) {
                        if (!headerSeen) {
                            if (!row.equals(headers)) throw new IllegalArgumentException("Unexpected headers: " + path + " " + row);
                            headerSeen = true;
                        } else if (row.stream().anyMatch(s -> !s.isBlank())) consumer.accept(List.copyOf(row));
                    }
                };
                var parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(), null,
                    reader.getSharedStringsTable(), handler, new DataFormatter(Locale.ROOT), false));
                parser.parse(new org.xml.sax.InputSource(sheet));
                if (!handler.headerSeen) throw new IllegalArgumentException("Missing header: " + path);
            }
            if (sheets.hasNext()) throw new IllegalArgumentException("Unexpected extra sheet: " + path);
        }
    }
}
