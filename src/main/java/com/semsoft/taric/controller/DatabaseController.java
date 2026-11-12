package com.semsoft.taric.controller;

import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabaseController {

    private final DSLContext dsl;

    public DatabaseController(DSLContext dsl) {
        this.dsl = dsl;
    }

    @GetMapping("/api/database")
    public Integer database() {
        return dsl.fetchCount(
            org.jooq.impl.DSL.table("app_info")
        );
    }
}