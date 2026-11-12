package com.semsoft.taric.ingestion;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static com.semsoft.taric.ingestion.Source.Table.*;
import static org.assertj.core.api.Assertions.*;

class ConsolidatorTest {
    static final LocalDate DATE = LocalDate.of(2026,9,9);
    static final String LAW = "Regulation 0111/05";
    static Source fixture() {
        var source = new Source();
        goods(source, "2914110000", "Acetone");
        area(source, "1011", "ERGA OMNES");
        area(source, "1008", "Third countries");
        area(source, "RU", "Russia"); area(source, "IR", "Iran"); area(source, "FR", "France");
        member(source, "1008", "RU"); member(source, "1008", "IR");
        source.publish(LEGAL, List.of(LAW, "L22", "1", "26-01-2005"));
        return source;
    }
    static void goods(Source source, String code, String description) {
        source.publish(GOODS, List.of(code + " 80", "01-01-2000", "", "EN", "6", "", description, "01-01-2000"));
    }
    static List<String> duty(String code, String law, String area, String type) {
        return List.of(code, "", "", "01-01-2020", "", "", area, "Export control", law, "Cond: same scope", area, type);
    }
    static void area(Source source, String code, String text) {
        source.publish(AREAS, List.of(code, "EN", code, text, "01-01-2000", "01-01-2000", ""));
    }
    static void member(Source source, String group, String country) {
        source.publish(MEMBERS, List.of(group, "01-01-2000", "EN", "", "", country, "", "", "01-01-2000", "", ""));
    }
    static void note(Source source, List<String> duty, String code, String text) {
        source.publish(NOTES, List.of(code, "EN", text, "01-01-2000", "01-01-2000", ""));
        source.publish(LINKS, List.of(duty.get(0), duty.get(1), duty.get(2), duty.get(10), duty.get(3), duty.get(4), duty.get(11), "", "", code));
    }
    static void exclude(Source source, List<String> duty, String country) {
        source.publish(EXCLUSIONS, List.of(duty.get(0), "", "", duty.get(3), "", "", "", "Country label, not code", duty.get(10), duty.get(11), country));
    }
    static Consolidator.Result consolidate(Source source) { return new Consolidator().consolidate(source, DATE); }
    static List<String> changed(List<String> row, int column, String value) {
        var result = new ArrayList<>(row); result.set(column,value); return result;
    }
    @Test void drugPrecursorsHaveLegalAndFootnoteEvidence() {
        var source = fixture(); var duty = duty("2914110000", LAW, "RU", "467");
        source.publish(EXPORT, duty); note(source,duty,"CD259","Export authorization - Regulation (EC) No 111/05");
        var m = consolidate(source).measures().getFirst();
        assertThat(m.categories()).containsEntry("drug_precursor", "legal_reference");
        assertThat(m.footnotes()).containsExactly("CD259");
        assertThat(m.legalUrl()).endsWith("32005R0111");
    }
    @Test void dualUseTypeAndExplicitMilitaryFootnoteAreRecognized() {
        var source = fixture(); goods(source,"2844100000","Natural uranium"); goods(source,"9306210000","Cartridges");
        source.publish(EXPORT,duty("2844101000", LAW,"RU","478"));
        var military = duty("9306210000",LAW,"RU","717"); source.publish(EXPORT,military);
        note(source,military,"TM612","Goods on the Common Military List");
        assertThat(consolidate(source).measures()).anySatisfy(m -> {
            assertThat(m.signature().hs6()).isEqualTo("284410"); assertThat(m.categories()).containsKey("dual_use");
        }).anySatisfy(m -> {
            assertThat(m.signature().hs6()).isEqualTo("930621"); assertThat(m.categories()).containsEntry("military_list","footnote");
        });
        assertThat(Rules.categories("717", "", "", List.of())).doesNotContainKey("military_list");
        assertThat(Rules.categories("767", "", "", List.of("Regulation (EU) 2021/821"))).containsEntry("dual_use","footnote");
    }
    @Test void subdivisionsMergeWithUnionOfFootnotes() {
        var source = fixture();
        var a = duty("2914111000", LAW,"RU","467"); var b = duty("2914112000", LAW,"RU","467");
        source.publish(EXPORT,a); source.publish(EXPORT,b);
        note(source,a,"CD259","A"); note(source,b,"TM135","B");
        var result = consolidate(source);
        assertThat(result.measures()).hasSize(1);
        assertThat(result.measures().getFirst().footnotes()).containsExactlyInAnyOrder("CD259","TM135");
    }
    @Test void distinctLawsAreNotMergedAndShareFootnotes() {
        var source = fixture();
        var a = duty("2914111000",LAW,"RU","467"); var b = duty("2914112000","Regulation 0273/04","RU","467");
        source.publish(EXPORT,a); source.publish(EXPORT,b); note(source,a,"CD259","Text"); note(source,b,"CD259","Text");
        var result = consolidate(source);
        assertThat(result.measures()).hasSize(2).allSatisfy(m -> assertThat(m.footnotes()).containsExactly("CD259"));
        assertThat(result.footnotes()).hasSize(1);
    }
    @Test void distinctGeographiesAndScopesAreNotMerged() {
        var source = fixture(); var a = duty("2914111000",LAW,"RU","467");
        source.publish(EXPORT,a);
        source.publish(EXPORT,duty("2914112000",LAW,"IR","467"));
        source.publish(EXPORT,changed(duty("2914113000",LAW,"RU","467"),9,"Different legal conditions"));
        assertThat(consolidate(source).measures()).hasSize(3);
    }
    @Test void nestedGroupsAndExclusionsAreResolvedBeforeConsolidation() {
        var source = fixture(); area(source,"G1","Group"); area(source,"G2","Nested");
        member(source,"G1","G2"); member(source,"G2","RU"); member(source,"G2","IR");
        var a = duty("2914110000",LAW,"G1","467"); source.publish(EXPORT,a); exclude(source,a,"IR");
        assertThat(consolidate(source).measures().getFirst().countries()).containsExactly("RU");
    }
    @Test void ergaOmnesIsUniversalButThirdCountriesRemainExplicit() {
        var source = fixture();
        assertThat(new Geography(source,DATE).resolve("1011")).contains("FR","IR","RU");
        assertThat(new Geography(source,DATE).resolve("1008")).contains("IR","RU").doesNotContain("FR");
    }
    @Test void geographyRejectsCyclesAndUnknownAreas() {
        var source = fixture(); area(source,"G1","Group"); area(source,"G2","Group");
        member(source,"G1","G2"); member(source,"G2","G1");
        var geo = new Geography(source,DATE);
        assertThatThrownBy(() -> geo.resolve("G1")).hasMessageContaining("cycle");
        assertThatThrownBy(() -> geo.resolve("4040")).hasMessageContaining("Unknown");
    }
    @Test void deletingOneSubdivisionRecalculatesAndRetainsTheMeasure() {
        var source = fixture(); var a = duty("2914111000",LAW,"RU","467"); var b = duty("2914112000",LAW,"RU","467");
        source.publish(EXPORT,a); source.publish(EXPORT,b);
        Ingestion.apply(source,List.of(new Ingestion.Change(Ingestion.Operation.DELETE,EXPORT,EXPORT.key(a),null)));
        assertThat(consolidate(source).measures()).hasSize(1);
        assertThat(source.rows(EXPORT)).containsExactly(b);
    }
    @Test void publishReplacesSourceIdentityInsteadOfAppending() {
        var source = fixture(); var a = duty("2914111000",LAW,"RU","467"); source.publish(EXPORT,a);
        var replacement = changed(a,9,"New conditions");
        Ingestion.apply(source,List.of(new Ingestion.Change(Ingestion.Operation.PUBLISH,EXPORT,EXPORT.key(replacement),replacement)));
        assertThat(source.rows(EXPORT)).containsExactly(replacement);
    }
    @Test void unknownDeletesAndWrongPublishKeysFail() {
        var source = fixture(); var a = duty("2914110000",LAW,"RU","467");
        assertThatThrownBy(() -> Ingestion.apply(source,List.of(new Ingestion.Change(Ingestion.Operation.DELETE,EXPORT,"unknown",null))))
            .hasMessageContaining("Unknown DELETE");
        assertThatThrownBy(() -> Ingestion.apply(source,List.of(new Ingestion.Change(Ingestion.Operation.PUBLISH,EXPORT,"wrong",a))))
            .hasMessageContaining("key mismatch");
    }
    @Test void directionsAreInvertedAndInactiveMeasuresAreOmitted() {
        var source = fixture(); var a = duty("2914110000",LAW,"RU","467");
        source.publish(EXPORT,a); source.publish(IMPORT,a);
        source.publish(EXPORT,changed(duty("2914111000",LAW,"IR","467"),3,"10-09-2026"));
        source.publish(EXPORT,changed(duty("2914112000",LAW,"IR","467"),4,"08-09-2026"));
        assertThat(consolidate(source).measures()).hasSize(2).extracting(m -> m.signature().direction()).containsExactlyInAnyOrder("import","export");
    }
    @Test void chapterMeasuresExpandToAllDescendantHs6() {
        var source = fixture(); goods(source,"2914120000","Other");
        source.publish(EXPORT,duty("2900000000",LAW,"RU","467"));
        assertThat(consolidate(source).measures()).extracting(m -> m.signature().hs6()).containsExactly("291411","291412");
    }
    @Test void descriptionsUseTheExactHs6RatherThanItsSubdivisions() {
        var source = fixture(); goods(source,"2914111000","Different subdivision");
        source.publish(EXPORT,duty("2914111000",LAW,"RU","467"));
        assertThat(consolidate(source).goods()).containsEntry("291411","Acetone");
    }
    @Test void unknownLegalUrlsAreReportedAndNeverInvented() {
        var warnings = new TreeSet<String>();
        assertThat(Rules.legalUrl("Unknown act",DATE,warnings)).isNull();
        assertThat(Rules.legalUrl(LAW,null,warnings)).isNull();
        assertThat(warnings).contains("EURLEX_UNRESOLVED: Unknown act", "EURLEX_UNRESOLVED: " + LAW);
        assertThat(Rules.legalUrl(LAW,LocalDate.of(2005,1,26),warnings)).endsWith("32005R0111");
    }
    @Test void unknownTypesFailAndFiscalTypesAreExcluded() {
        assertThat(Rules.action("103")).isNull();
        assertThatThrownBy(() -> Rules.action("999")).hasMessageContaining("Unmapped");
    }
    @Test void missingFootnoteTextPreventsPublishingAnIncompleteExplanation() {
        var source = fixture(); var a = duty("2914110000",LAW,"RU","467"); source.publish(EXPORT,a); note(source,a,"CD259","Text");
        source.tables.get(NOTES).clear();
        assertThatThrownBy(() -> consolidate(source)).hasMessageContaining("Missing active English footnote");
    }
    @Test void deletingTheLastMeasureLeavesAValidEmptyBusinessResult() {
        var source = fixture(); var a = duty("2914110000",LAW,"RU","467"); source.publish(EXPORT,a);
        source.delete(EXPORT,EXPORT.key(a));
        assertThat(consolidate(source).measures()).isEmpty();
    }
    @Test void euAliasUsesTheSourceMembershipAndSerbiaUsesIsoCode() {
        var source = fixture(); area(source,"EU","European Union"); area(source,"1010","Union");
        area(source,"XS","Serbia"); member(source,"1010","FR");
        var geo = new Geography(source,DATE);
        assertThat(geo.resolve("EU")).containsExactly("FR");
        assertThat(geo.resolve("XS")).containsExactly("RS");
    }

}
