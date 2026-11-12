package com.semsoft.taric.service;

import com.semsoft.taric.model.MeasureResult;
import com.semsoft.taric.repository.MeasureRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class MeasureSearchService {

    private final MeasureRepository repository;

    public MeasureSearchService(MeasureRepository repository) {
        this.repository = repository;
    }

    public List<MeasureResult> search(String hs6, String direction, String country) {

        if (hs6 == null || !hs6.matches("[0-9]{6}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "HS code must contain 6 digits");
        }

        if (!"import".equals(direction) && !"export".equals(direction)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Direction must be import or export");
        }

        if (country == null || !country.matches("(?i)[A-Z]{2}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Country must be a 2-letter code");
        }

        return repository.search(hs6, direction, country.toUpperCase(Locale.ROOT));
    }
}