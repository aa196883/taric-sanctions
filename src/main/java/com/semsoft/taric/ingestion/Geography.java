package com.semsoft.taric.ingestion;

import java.time.LocalDate;
import java.util.*;
import static com.semsoft.taric.ingestion.Source.*;

final class Geography {
    private final Set<String> areas = new HashSet<>();
    private final Map<String, Set<String>> members = new HashMap<>();
    private final Map<String, Set<String>> cache = new HashMap<>();
    private final Set<String> countries = new TreeSet<>();
    Geography(Source source, LocalDate asOf) {
        for (var row : source.rows(Table.AREAS)) {
            if (!row.get(1).equals("EN") || !active(row, 4, 6, asOf)) continue;
            areas.add(row.get(0));
            if (row.get(0).matches("[A-Z]{2}") && !row.get(0).equals("EU")) countries.add(country(row.get(0)));
        }
        for (var row : source.rows(Table.MEMBERS)) {
            if (!active(row, 1, 10, asOf) || !active(row, 8, 9, asOf)) continue;
            members.computeIfAbsent(row.get(0), ignored -> new TreeSet<>()).add(row.get(5));
        }
    }
    static String country(String value) { return value.equals("XS") ? "RS" : value; }
    Set<String> resolve(String area) { return resolve(area, new HashSet<>()); }
    private Set<String> resolve(String area, Set<String> path) {
        if (area.equals("EU")) return resolve("1010", path);
        if (cache.containsKey(area)) return cache.get(area);
        if (!areas.contains(area)) throw new IllegalArgumentException("Unknown geographical area: " + area);
        if (!path.add(area)) throw new IllegalArgumentException("Geographical cycle: " + path + " -> " + area);
        var result = new TreeSet<String>();
        if (area.equals("1011")) result.addAll(countries); // ERGA OMNES; 1008 uses explicit membership.
        else if (members.containsKey(area)) for (String member : members.get(area)) result.addAll(resolve(member, path));
        else if (area.matches("[A-Z]{2}") && !area.equals("EU")) result.add(country(area));
        else throw new IllegalArgumentException("Empty/unresolved geographical group: " + area);
        path.remove(area);
        cache.put(area, Collections.unmodifiableSet(result));
        return cache.get(area);
    }
}
