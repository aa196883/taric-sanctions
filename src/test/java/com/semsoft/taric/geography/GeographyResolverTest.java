package com.semsoft.taric.geography;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class GeographyResolverTest {

    private static GeographyResolver resolver;

    @BeforeAll
    static void setUp() throws IOException {

        Path file = Path.of(
                "resources/taric/full/Geographical areas composition.xlsx"
        );

        resolver = new GeographyResolver(file);
    }

    @Test
    void resolveDirectCountry() {
        assertEquals(
                java.util.Set.of("RU"),
                resolver.resolve("RU")
        );
    }

    @Test
    void resolveAllThirdCountries() {

        var countries = resolver.resolve("1008");

        assertEquals(217, countries.size());
        assertTrue(countries.contains("RU"));
        assertTrue(countries.contains("IR"));
    }

    @Test
    void resolveErgaOmnes() {

        var countries = resolver.resolve("1011");

        assertEquals(245, countries.size());

        assertTrue(countries.contains("FR"));
        assertTrue(countries.contains("RU"));
        assertTrue(countries.contains("IR"));

        assertFalse(countries.contains("EU"));
    }

    @Test
    void resolveEuropeanUnionAbbreviation() {

        assertEquals(
                resolver.resolve("1010"),
                resolver.resolve("EU")
        );

        assertEquals(28, resolver.resolve("EU").size());
    }

    @Test
    void resolveEuropeanUnion() {

        var countries = resolver.resolve("1010");

        assertEquals(28, countries.size());
        assertTrue(countries.contains("FR"));
        assertTrue(countries.contains("XI"));
    }

    @Test
    void resolveSmallGroup() {

        var countries = resolver.resolve("1033");

        assertEquals(15, countries.size());
        assertTrue(countries.contains("AG"));
        assertTrue(countries.contains("BB"));
    }

    @Test
    void rejectUnknownCode() {

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("ZZ")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("9999")
        );
    }

    @Test
    void resolutionDoesNotModifyGroups() {

        var countries = new HashSet<>(resolver.resolve("1008"));

        countries.remove("RU");

        assertFalse(countries.contains("RU"));
        assertTrue(resolver.resolve("1008").contains("RU"));
    }
}