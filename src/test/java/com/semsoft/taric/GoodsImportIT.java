package com.semsoft.taric;

import com.semsoft.taric.service.GoodsImportService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "taric.auto-import=false")
@Testcontainers
class GoodsImportIT {

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
    GoodsImportService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void importNomenclature() throws Exception {

        Path file = Path.of("resources/taric/full/Nomenclature EN.xlsx");

        // First import
        assertTrue(service.importFile(file));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM goods",
                Integer.class
        );

        assertEquals(22349, count);

        // Second import must be skipped
        assertFalse(service.importFile(file));

        // Data must remain unchanged
        Integer countAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM goods",
                Integer.class
        );

        assertEquals(count, countAfter);
    }
}
