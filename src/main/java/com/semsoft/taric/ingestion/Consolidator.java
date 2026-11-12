package com.semsoft.taric.ingestion;

import java.time.LocalDate;
import java.util.*;
import static com.semsoft.taric.ingestion.Source.*;

public final class Consolidator {
    public record Signature(String hs6, String direction, String action, String legalReference,
                            String scopeKey, String geographyKey) {}
    public record Measure(Signature signature, String legalUrl, Set<String> countries,
                          Map<String, String> categories, Set<String> footnotes) {}
    public record Result(Map<String, String> goods, List<Measure> measures,
                         Map<String, String> footnotes, Set<String> warnings) {}

    public Result consolidate(Source source, LocalDate asOf) {
        var warnings = new TreeSet<String>();
        var goods = goods(source, asOf, warnings);
        var geography = new Geography(source, asOf);
        var footnotes = descriptions(source, asOf);
        var links = new HashMap<List<String>, Set<String>>();
        var exclusions = new HashMap<List<String>, Set<String>>();
        for (var r : source.rows(Table.LINKS)) if (active(r, 4, 5, asOf))
            links.computeIfAbsent(select(r, 0,1,2,4,3,6), ignored -> new TreeSet<>()).add(r.get(9));
        for (var r : source.rows(Table.EXCLUSIONS)) if (active(r, 3, 4, asOf))
            // The supplied workbook has swapped captions: K contains the actual country CODE.
            exclusions.computeIfAbsent(select(r, 0,1,2,3,8,9), ignored -> new TreeSet<>()).add(r.get(10));
        var publications = new HashMap<String, LocalDate>();
        for (var r : source.rows(Table.LEGAL)) if (!r.get(3).isBlank()) publications.merge(r.get(0), date(r.get(3)), (a,b) -> a.isBefore(b) ? a : b);
        var consolidated = new LinkedHashMap<Signature, Measure>();
        var targets = new HashMap<String, List<String>>();
        for (var table : List.of(Table.IMPORT, Table.EXPORT)) for (var r : source.rows(table)) {
            String action = Rules.action(r.get(11));
            if (action == null || !active(r, 3, 4, asOf)) continue;
            if (!r.get(0).matches("\\d{10}")) throw new IllegalArgumentException("Invalid goods code: " + r.get(0));
            var key = select(r, 0,1,2,3,10,11);
            var notes = links.getOrDefault(key, Set.of());
            for (var note : notes) if (!footnotes.containsKey(note))
                throw new IllegalArgumentException("Missing active English footnote: " + note);
            var countries = new TreeSet<>(geography.resolve(r.get(10)));
            for (String excluded : exclusions.getOrDefault(key, Set.of())) countries.removeAll(geography.resolve(excluded));
            if (countries.isEmpty()) { warnings.add("EMPTY_GEOGRAPHY: " + key); continue; }
            String legal = r.get(8);
            if (legal.isBlank()) throw new IllegalArgumentException("Missing legal reference: " + key);
            String url = Rules.legalUrl(legal, publications.get(legal), warnings);
            var categories = Rules.categories(r.get(11), r.get(7), legal, notes.stream().map(footnotes::get).toList());
            // Conditions aren't evaluated or exposed. Their fingerprint prevents merging distinct legal scopes.
            String scope = hash(select(r, 1,2,7,9).toString());
            String zone = hash(countries.toString());
            var hsCodes = targets.computeIfAbsent(r.get(0), code -> targets(code, goods.keySet()));
            if (hsCodes.isEmpty()) throw new IllegalArgumentException("No HS6 for measure: " + r.get(0));
            for (String hs6 : hsCodes) {
                var signature = new Signature(hs6, table == Table.IMPORT ? "export" : "import", action, legal, scope, zone);
                var measure = consolidated.computeIfAbsent(signature, s -> new Measure(s, url, Set.copyOf(countries), new TreeMap<>(), new TreeSet<>()));
                categories.forEach((category, evidence) -> measure.categories.merge(category, evidence,
                    (a, b) -> a.compareTo(b) <= 0 ? a : b));
                measure.footnotes.addAll(notes);
            }
        }
        if (goods.isEmpty()) throw new IllegalArgumentException("Empty nomenclature snapshot");
        var usedNotes = new TreeMap<String, String>();
        consolidated.values().forEach(m -> m.footnotes.forEach(code -> usedNotes.put(code, footnotes.get(code))));
        return new Result(goods, List.copyOf(consolidated.values()), usedNotes, warnings);
    }

    static List<String> targets(String code, Set<String> hs6s) {
        String hs6 = code.substring(0, 6);
        if (hs6s.contains(hs6)) return List.of(hs6);
        String prefix = code.endsWith("00000000") ? code.substring(0, 2)
            : code.endsWith("000000") ? code.substring(0, 4) : hs6;
        return hs6s.stream().filter(s -> s.startsWith(prefix)).sorted().toList();
    }
    private static List<String> select(List<String> row, int... columns) {
        return Arrays.stream(columns).mapToObj(row::get).toList();
    }
    private static Map<String, String> descriptions(Source source, LocalDate asOf) {
        var chosen = new HashMap<String, List<String>>();
        for (var r : source.rows(Table.NOTES)) {
            if (!r.get(1).equals("EN") || !active(r, 3, 5, asOf) || date(r.get(4)).isAfter(asOf)) continue;
            chosen.merge(r.get(0), r, (a,b) -> date(a.get(4)).isAfter(date(b.get(4))) ? a : b);
        }
        var result = new HashMap<String, String>();
        chosen.forEach((code, row) -> result.put(code, row.get(2)));
        return result;
    }
    private static Map<String, String> goods(Source source, LocalDate asOf, Set<String> warnings) {
        var rows = source.rows(Table.GOODS).stream()
            .filter(r -> r.get(3).equals("EN") && active(r, 1, 2, asOf) && !date(r.get(7)).isAfter(asOf))
            .sorted(Comparator.comparing((List<String> r) -> r.get(0)).thenComparing(r -> date(r.get(7)))).toList();
        var codes = new TreeSet<String>();
        rows.forEach(r -> codes.add(r.get(0).substring(0, 6)));
        var leaves = new TreeSet<>(codes);
        // Chapter/heading rows are not HS6 when more specific subheadings exist.
        leaves.removeIf(code -> code.endsWith("00") && codes.stream().anyMatch(other -> !other.equals(code)
            && other.startsWith(code.substring(0, code.endsWith("0000") ? 2 : 4))));
        var result = new TreeMap<String, String>();
        var selected = new HashMap<String, List<String>>();
        Comparator<List<String>> preference = Comparator
            .comparing((List<String> r) -> !r.get(0).substring(6, 10).equals("0000"))
            .thenComparing(r -> !r.get(0).endsWith("80"))
            .thenComparing(r -> r.get(0))
            .thenComparing(r -> date(r.get(7)), Comparator.reverseOrder());
        var ancestors = new TreeMap<Integer, String>();
        var labels = new HashMap<List<String>, String>();
        for (var r : rows) {
            int indent = (int) r.get(5).chars().filter(c -> c == '-').count();
            ancestors.tailMap(indent).clear();
            ancestors.put(indent, r.get(6));
            labels.put(r, String.join(" > ", ancestors.values()));
            String hs6 = r.get(0).substring(0, 6);
            if (leaves.contains(hs6)) selected.merge(hs6, r, (a,b) -> preference.compare(a,b) <= 0 ? a : b);
        }
        selected.forEach((hs6, row) -> {
            if (!row.get(0).substring(6,10).equals("0000")) warnings.add("HS6_DESCRIPTION_FALLBACK: " + hs6 + " <- " + row.get(0));
            result.put(hs6, labels.get(row));
        });
        return result;
    }
}
