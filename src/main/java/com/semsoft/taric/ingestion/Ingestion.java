package com.semsoft.taric.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@Service
public class Ingestion {
    private final TaricStore store;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    public Ingestion(TaricStore store, PlatformTransactionManager transactions, ObjectMapper json) {
        this.store = store;
        this.transaction = new TransactionTemplate(transactions);
        this.json = json;
    }
    public Consolidator.Result full(Path directory, LocalDate asOf) throws Exception {
        var loaded = FullSnapshot.read(directory);
        var result = new Consolidator().consolidate(loaded.source(), asOf);
        transaction.executeWithoutResult(status -> {
            store.lock();
            store.publish(loaded.source(), result, asOf, "full/" + asOf, directory.toAbsolutePath().toUri().toString(), loaded.checksums(), null);
        });
        return result;
    }
    public enum Operation { PUBLISH, DELETE }
    public record Change(Operation operation, Source.Table table, String key, List<String> cells) {}
    public record Delta(LocalDate previousAsOf, LocalDate asOf, List<Change> changes) {}
    /** Trusted transport metadata must be supplied separately from the untrusted payload. */
    public record VerifiedDownload(URI origin, String sha256) {}

    // Adapter contract for future TARIC Daily XLSX/XML parsing. This method accepts normalized JSON, not raw Daily workbooks.
    public Consolidator.Result daily(Path normalizedJson, VerifiedDownload trusted) throws Exception {
        if (!"https".equals(trusted.origin().getScheme()) || !"ec.europa.eu".equals(trusted.origin().getHost())
            || trusted.origin().getUserInfo() != null) throw new IllegalArgumentException("Untrusted TARIC provenance");
        byte[] bytes = Files.readAllBytes(normalizedJson);
        if (!Source.hash(bytes).equalsIgnoreCase(trusted.sha256())) throw new IllegalArgumentException("Checksum mismatch");
        var delta = json.readValue(bytes, Delta.class);
        if (!delta.asOf().equals(delta.previousAsOf().plusDays(1))) throw new IllegalArgumentException("Non-sequential Daily Update");
        return transaction.execute(status -> {
            store.lock();
            if (!store.asOf().equals(delta.previousAsOf())) throw new IllegalArgumentException("Daily Update base date mismatch");
            if (!Rules.VERSION.equals(store.rulesVersion())) throw new IllegalArgumentException("Rules changed: rebuild full snapshot before applying deltas");
            var source = store.load();
            var before = new Consolidator().consolidate(source, delta.previousAsOf());
            apply(source, delta.changes());
            var after = new Consolidator().consolidate(source, delta.asOf());
            // Comparing full HS6 results also captures shared references, memberships and midnight validity transitions.
            var oldRows = byHs6(before);
            var newRows = byHs6(after);
            var impacted = new TreeSet<>(oldRows.keySet());
            impacted.addAll(newRows.keySet());
            impacted.removeIf(hs -> Objects.equals(oldRows.get(hs), newRows.get(hs)));
            store.publish(source, after, delta.asOf(), "daily/" + delta.asOf(), trusted.origin().toString(),
                Map.of(normalizedJson.getFileName().toString(), trusted.sha256()), impacted);
            return after;
        });
    }
    static void apply(Source source, List<Change> changes) {
        for (var change : changes) {
            Objects.requireNonNull(change.operation());
            if (change.operation() == Operation.DELETE) source.delete(change.table(), change.key());
            else {
                if (!change.table().key(change.cells()).equals(change.key())) throw new IllegalArgumentException("PUBLISH key mismatch");
                source.publish(change.table(), change.cells());
            }
        }
    }
    private static Map<String, Set<Consolidator.Measure>> byHs6(Consolidator.Result result) {
        var rows = new HashMap<String, Set<Consolidator.Measure>>();
        result.measures().forEach(m -> rows.computeIfAbsent(m.signature().hs6(), ignored -> new HashSet<>()).add(m));
        return rows;
    }
}
