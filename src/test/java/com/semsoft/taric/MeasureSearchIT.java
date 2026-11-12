package com.semsoft.taric;

import com.semsoft.taric.service.MeasureSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class MeasureSearchIT {

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
    MeasureSearchService service;

    @Test
    @Sql("/sample-data.sql")
    void searchMeasures() {

        var iran = service.search("291411", "export", "IR");

        assertEquals(2, iran.size());

        assertTrue(iran.stream()
                .allMatch(m -> m.categories().contains("drug_precursor")));

        assertTrue(iran.stream()
                .allMatch(m -> m.footnotes().stream()
                        .anyMatch(f -> f.code().equals("CD259"))));

        var russia = service.search("291411", "export", "RU");

        assertEquals(1, russia.size());
        assertEquals(9001L, russia.get(0).id());

        var imports = service.search("291411", "import", "IR");

        assertTrue(imports.isEmpty());
    }
}