package com.semsoft.taric.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.*;
import java.net.URI;
import java.util.*;
import static com.semsoft.taric.ingestion.ConsolidatorTest.*;
import static com.semsoft.taric.ingestion.Source.Table.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit opt-in; creates its own PostgreSQL 17 container, never cleans the project database. */
@EnabledIfSystemProperty(named="taric.postgres.tests", matches="true")
class PostgresIngestionTest {
    static PostgreSQLContainer<?> postgres;
    static JdbcTemplate jdbc;
    static Ingestion ingestion;
    static TaricStore store;
    static TransactionTemplate transaction;
    static ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @TempDir Path directory;
    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer<>("postgres:17"); postgres.start();
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds); store = new TaricStore(jdbc,json);
        var manager = new DataSourceTransactionManager(ds); transaction = new TransactionTemplate(manager);
        ingestion = new Ingestion(store,manager,json);
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @BeforeEach void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_publish ON measures");
        jdbc.execute("TRUNCATE measures, goods, footnotes, measure_countries, measure_categories, measure_footnotes, database_state, taric_source.rows, taric_source.manifest CASCADE");
    }
    Source initial() throws Exception {
        var source = fixture(); var a = duty("2914111000",LAW,"RU","467"); var b = duty("2914112000",LAW,"RU","467");
        source.publish(EXPORT,a); source.publish(EXPORT,b); note(source,a,"CD259","Regulation (EC) No 111/05"); note(source,b,"CD259","Regulation (EC) No 111/05");
        writeFull(source); ingestion.full(directory, DATE); return source;
    }
    void writeFull(Source source) throws Exception {
        for (var table : Source.Table.values()) try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Data"); int n=0;
            var rows = new ArrayList<List<String>>(); rows.add(table.headers); rows.addAll(source.rows(table));
            for (var cells : rows) { var row = sheet.createRow(n++); for (int c=0;c<cells.size();c++) row.createCell(c).setCellValue(cells.get(c)); }
            try (var out = Files.newOutputStream(directory.resolve(table.file))) { book.write(out); }
        }
    }
    Path delta(List<Ingestion.Change> changes) throws Exception {
        var path = directory.resolve("daily.json");
        json.writeValue(path.toFile(),new Ingestion.Delta(DATE,DATE.plusDays(1),changes)); return path;
    }
    Ingestion.VerifiedDownload trusted(Path path) throws Exception {
        return new Ingestion.VerifiedDownload(URI.create("https://ec.europa.eu/taxation_customs/dds2/taric/daily"),Source.hash(Files.readAllBytes(path)));
    }
    @Test void fullPublishesAQueryableMinimalCurrentState() throws Exception {
        initial();
        assertThat(store.asOf()).isEqualTo(DATE);
        assertThat(jdbc.queryForObject("SELECT source_snapshot FROM database_state",String.class)).isEqualTo("full/2026-09-09");
        var found = new com.semsoft.taric.MeasureSearch(jdbc).find("291411","export","RU");
        assertThat(found).hasSize(1); assertThat(found.getFirst().direction()).isEqualTo("import");
        assertThat(found.getFirst().categories()).contains("drug_precursor");
        assertThat(found.getFirst().footnotes()).hasSize(1);
        assertThat(new com.semsoft.taric.MeasureSearch(jdbc).find("291411","export","IR")).isEmpty();
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='measures'",String.class))
            .doesNotContain("valid_from","valid_to","measure_type");
        assertThat(new Consolidator().consolidate(store.load(),DATE).goods()).isEqualTo(Map.of("291411", "Acetone"));
    }
    @Test void deleteRecalculatesWholeHs6AndKeepsTheRemainingSubdivision() throws Exception {
        initial(); var a = duty("2914111000",LAW,"RU","467");
        var path = delta(List.of(new Ingestion.Change(Ingestion.Operation.DELETE,EXPORT,EXPORT.key(a),null)));
        ingestion.daily(path,trusted(path));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measures",Integer.class)).isEqualTo(1);
        assertThat(store.load().rows(EXPORT)).hasSize(1);
        assertThat(store.asOf()).isEqualTo(DATE.plusDays(1));
        assertThatThrownBy(() -> ingestion.daily(path,trusted(path))).hasMessageContaining("base date");
    }
    @Test void changedGeographyRecalculatesAllDependentMeasures() throws Exception {
        var source = initial();
        var a = duty("2914111000",LAW,"1008","467"); source.publish(EXPORT,a); writeFull(source); ingestion.full(directory,DATE);
        var member = source.rows(MEMBERS).stream().filter(r -> r.get(5).equals("IR")).findFirst().orElseThrow();
        var path = delta(List.of(new Ingestion.Change(Ingestion.Operation.DELETE,MEMBERS,MEMBERS.key(member),null)));
        ingestion.daily(path,trusted(path));
        assertThat(new com.semsoft.taric.MeasureSearch(jdbc).find("291411","export","IR")).isEmpty();
    }
    @Test void rejectedChecksumAndProvenanceLeaveTheActiveStateUntouched() throws Exception {
        initial(); var path = delta(List.of());
        assertThatThrownBy(() -> ingestion.daily(path,new Ingestion.VerifiedDownload(URI.create("https://ec.europa.eu/a"),"00"))).hasMessageContaining("Checksum");
        assertThatThrownBy(() -> ingestion.daily(path,new Ingestion.VerifiedDownload(URI.create("https://example.org/a"),trusted(path).sha256()))).hasMessageContaining("provenance");
        assertThat(store.asOf()).isEqualTo(DATE);
    }
    @Test void failedPublicationRollsBackBusinessSourceAndSnapshotTogether() throws Exception {
        initial(); var oldCount = jdbc.queryForObject("SELECT count(*) FROM taric_source.rows",Integer.class);
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test publication failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_publish BEFORE INSERT ON measures FOR EACH ROW EXECUTE FUNCTION reject_publish()");
        var replacement = changed(duty("2914111000",LAW,"RU","467"),9,"New scope");
        var path = delta(List.of(new Ingestion.Change(Ingestion.Operation.PUBLISH,EXPORT,EXPORT.key(replacement),replacement)));
        assertThatThrownBy(() -> ingestion.daily(path,trusted(path))).hasMessageContaining("test publication failure");
        assertThat(store.asOf()).isEqualTo(DATE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measures",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM taric_source.rows",Integer.class)).isEqualTo(oldCount);
        assertThat(store.load().rows(EXPORT)).doesNotContain(replacement);
    }
    @Test void midnightExpiryIsRecalculatedEvenWithoutGoodsChanges() throws Exception {
        var source = initial(); source.tables.get(EXPORT).clear();
        source.publish(EXPORT,changed(duty("2914111000",LAW,"RU","467"),4,"09-09-2026"));
        writeFull(source); ingestion.full(directory,DATE);
        var path = delta(List.of()); ingestion.daily(path,trusted(path));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM measures",Integer.class)).isZero();
        assertThat(store.asOf()).isEqualTo(DATE.plusDays(1));
    }

    @Test void aRulesVersionChangeRequiresAnExplicitRebuild() throws Exception {
        initial(); jdbc.update("UPDATE taric_source.manifest SET rules_version='old'");
        var path = delta(List.of());
        assertThatThrownBy(() -> ingestion.daily(path,trusted(path))).hasMessageContaining("Rules changed");
        assertThat(store.asOf()).isEqualTo(DATE);
    }

}
