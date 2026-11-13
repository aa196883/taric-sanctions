package com.semsoft.taric;

import com.semsoft.taric.service.GoodsImportService;
import com.semsoft.taric.service.MeasuresImportService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "taric.auto-import=false")
@Testcontainers
class MeasuresImportIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    GoodsImportService goodsService;

    @Autowired
    MeasuresImportService measuresService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void importMeasures() throws Exception {

        Path directory = Path.of("resources/taric/full");

        long goodsStart = System.nanoTime();
        goodsService.importFile(
                directory.resolve("Nomenclature EN.xlsx")
        );

        System.out.printf("PROFILE goods import: %.3f s%n", (System.nanoTime() - goodsStart) / 1e9);
        Path imports = directory.resolve("Duties Import 01-99.xlsx");
        Path exports = directory.resolve("Duties Export 01-99.xlsx");

        Path geography = directory.resolve(
                "Geographical areas composition.xlsx"
        );

        Path exclusions = directory.resolve(
                "Measure exclusions.xlsx"
        );

        assertTrue(measuresService.importFiles(
                imports, exports, geography, exclusions
        ));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measures",
                Integer.class
        );

        assertTrue(count > 0);

        // Every imported measure has exactly one direct country
        Integer countries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measure_countries",
                Integer.class
        );

        assertTrue(countries > count);

        // Common Military List classification
        Integer military = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM measure_categories
                WHERE category = 'military_list'
                """,
                Integer.class
        );

        assertTrue(military > 0);

        // Both directions must be represented
        Integer directions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT direction) FROM measures",
                Integer.class
        );

        assertEquals(2, directions);

        // Second import is skipped
        assertFalse(measuresService.importFiles(imports, exports, geography, exclusions));

        Integer countAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measures",
                Integer.class
        );

        assertEquals(count, countAfter);

        Long measureId = jdbc.queryForObject(
                """
                SELECT id
                FROM measures
                WHERE goods_code = '0100000000'
                AND direction = 'import'
                AND measure_type_code = '750'
                AND legal_reference = 'Regulation 2306/21'
                """,
                Long.class
        );

        Integer france = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM measure_countries
                WHERE measure_id = ?
                AND country_code = 'FR'
                """,
                Integer.class,
                measureId
        );

        Integer iceland = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM measure_countries
                WHERE measure_id = ?
                AND country_code = 'IS'
                """,
                Integer.class,
                measureId
        );

        assertEquals(1, france);
        assertEquals(0, iceland);

        Integer dualUse = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM measure_categories
                WHERE category = 'dual_use'
                """,
                Integer.class
        );

        assertTrue(dualUse > 0);
    }
}