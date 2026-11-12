package com.semsoft.taric.ingestion;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.LocalDate;

@Component
@ConditionalOnProperty("taric.ingest.full")
class IngestFull implements ApplicationRunner {
    private final Ingestion ingestion;
    private final Environment environment;
    IngestFull(Ingestion ingestion, Environment environment) { this.ingestion = ingestion; this.environment = environment; }
    public void run(ApplicationArguments args) throws Exception {
        var result = ingestion.full(Path.of(environment.getRequiredProperty("taric.ingest.full")),
            LocalDate.parse(environment.getRequiredProperty("taric.ingest.as-of")));
        var log = org.slf4j.LoggerFactory.getLogger(IngestFull.class);
        result.warnings().forEach(log::warn);
        log.info("Published {} HS6, {} measures, {} footnotes; {} warnings", result.goods().size(), result.measures().size(), result.footnotes().size(), result.warnings().size());
    }
}
