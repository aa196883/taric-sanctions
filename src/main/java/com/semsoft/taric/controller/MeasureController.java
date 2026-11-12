package com.semsoft.taric.controller;

import com.semsoft.taric.model.MeasureResult;
import com.semsoft.taric.service.MeasureSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/measures")
public class MeasureController {

    private final MeasureSearchService service;

    public MeasureController(MeasureSearchService service) {
        this.service = service;
    }

    @GetMapping
    public List<MeasureResult> search(
            @RequestParam String hs6,
            @RequestParam String direction,
            @RequestParam String country) {

        return service.search(hs6, direction, country);
    }
}