package com.semsoft.taric.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class FullSnapshotTest {
    @Test void suppliedSnapshotCoversTheThreeRequiredGoods() throws Exception {
        Path path = Path.of("src/main/resources/taric/full");
        Assumptions.assumeTrue(Files.isDirectory(path), "Local TARIC full snapshot not supplied");
        var loaded = FullSnapshot.read(path);
        var result = new Consolidator().consolidate(loaded.source(), LocalDate.of(2026,9,9));
        assertThat(result.measures()).anySatisfy(m -> {
            assertThat(m.signature().hs6()).isEqualTo("284410");
            assertThat(m.categories()).containsKey("dual_use");
            assertThat(m.signature().direction()).isEqualTo("import");
        }).anySatisfy(m -> {
            assertThat(m.signature().hs6()).isEqualTo("930621");
            assertThat(m.categories()).containsEntry("military_list", "footnote");
        }).anySatisfy(m -> {
            assertThat(m.signature().hs6()).isEqualTo("291411");
            assertThat(m.signature().legalReference()).isEqualTo("Regulation 0111/05");
            assertThat(m.categories()).containsKey("drug_precursor");
            assertThat(m.footnotes()).contains("CD259");
            assertThat(m.legalUrl()).endsWith("32005R0111");
        });
        assertThat(result.goods().get("291411")).contains("Acetone");
        assertThat(result.measures().stream().map(Consolidator.Measure::signature)).doesNotHaveDuplicates();
        System.out.printf("FULL: goods=%d measures=%d countries=%d footnotes=%d warnings=%s%n", result.goods().size(), result.measures().size(),
            result.measures().stream().mapToInt(m -> m.countries().size()).sum(), result.footnotes().size(), result.warnings());
    }
}
