package com.semsoft.taric.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Detailed source state; positional columns follow validated workbook headers. */
public final class Source {
    public enum Table {
        GOODS("Nomenclature EN", "Goods code|Start date|End date|Language|Hier. Pos.|Indent|Description|Descr. start date", 0,1,3,7),
        IMPORT("Duties Import 01-99", "Goods code|Add code|Order No.|Start date|End date|RED_IND|Origin|Measure type|Legal base|Duty|Origin code|Meas. type code", 0,1,2,3,10,11,8),
        EXPORT("Duties Export 01-99", "Goods code|Add code|Order No.|Start date|End date|RED_IND|Destination|Measure type|Legal base|Duty|Dest. code|Meas. type code", 0,1,2,3,10,11,8),
        NOTES("Footnotes descriptions", "Footnote|Language|Description|Start date|Descr. start date|End date", 0,1,3,4),
        LINKS("Measure footnotes", "Goods code|Add code|Order No.|Origin code|Start date|End date|Meas. type code|Origin|Measure type|Footnote", 0,1,2,3,4,6,9),
        EXCLUSIONS("Measure exclusions", "Goods code|Add code|Order No.|Start date|End date|Origin|Measure type|Excluded country code|Origin code|Meas. type code|Excluded country", 0,1,2,3,8,9,10),
        AREAS("Geographical areas description", "Or./Dest.|Language|Abbrev.|Description|Start date|Descr. start date|End date", 0,1,4,5),
        MEMBERS("Geographical areas composition", "Country group|Start date|Language|Abbrev.|Country group descr.|Member country|Abbrev.|Description|Mbship start date|Mbship end date|End date", 0,1,2,5,8),
        LEGAL("Legal basis", "Legal base|Off. Journal|Page|Publ. date", 0,1,2,3);

        public final String file;
        final List<String> headers;
        final int[] keyColumns;
        Table(String file, String headers, int... keyColumns) {
            this.file = file + ".xlsx";
            this.headers = List.of(headers.split("\\|"));
            this.keyColumns = keyColumns;
        }
        public String key(List<String> row) {
            return hash(Arrays.stream(keyColumns).mapToObj(row::get).toList().toString());
        }
    }

    final Map<Table, Map<String, List<String>>> tables = new EnumMap<>(Table.class);
    public Source() { for (var table : Table.values()) tables.put(table, new LinkedHashMap<>()); }
    public Collection<List<String>> rows(Table table) { return tables.get(table).values(); }
    public void publish(Table table, List<String> row) {
        if (row.size() != table.headers.size()) throw new IllegalArgumentException("Invalid columns: " + table);
        tables.get(table).put(table.key(row), List.copyOf(row));
    }
    public void delete(Table table, String key) {
        if (tables.get(table).remove(key) == null) throw new IllegalArgumentException("Unknown DELETE: " + table + "/" + key);
    }
    public Source copy() {
        var result = new Source();
        tables.forEach((table, rows) -> result.tables.get(table).putAll(rows));
        return result;
    }
    static LocalDate date(String value) {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd-MM-uuuu"));
    }
    static boolean active(List<String> row, int start, int end, LocalDate asOf) {
        return (row.get(start).isBlank() || !date(row.get(start)).isAfter(asOf))
            && (row.get(end).isBlank() || !date(row.get(end)).isBefore(asOf));
    }
    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
