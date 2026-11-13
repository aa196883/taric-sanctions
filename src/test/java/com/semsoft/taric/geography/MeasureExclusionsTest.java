package com.semsoft.taric.geography;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeasureExclusionsTest {

    private static MeasureExclusions exclusions;

    @BeforeAll
    static void setUp() throws Exception {

        exclusions = new MeasureExclusions(
                Path.of("resources/taric/full/Measure exclusions.xlsx")
        );
    }

    @Test
    void loadExclusions() {
        assertEquals(10520, exclusions.size());
    }

    @Test
    void findExcludedCountries() {

        var key = new MeasureExclusions.MeasureKey(
                "0100000000",
                "",
                "",
                "01-01-2022",
                "1011",
                "750"
        );

        Set<String> countries = exclusions.getExcludedCountries(key);

        assertTrue(countries.contains("IS"));
        assertTrue(countries.contains("NO"));
        assertTrue(countries.contains("LI"));
        assertTrue(countries.contains("CH"));
    }

    @Test
    void noExclusionsForUnknownMeasure() {

        var key = new MeasureExclusions.MeasureKey(
                "9999999999", "", "", "01-01-2020", "1011", "750"
        );

        assertTrue(exclusions.getExcludedCountries(key).isEmpty());
    }
}