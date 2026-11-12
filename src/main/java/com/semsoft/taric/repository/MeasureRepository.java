package com.semsoft.taric.repository;

import com.semsoft.taric.model.MeasureResult;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.jooq.impl.DSL.*;

@Repository
public class MeasureRepository {

    private final DSLContext dsl;

    public MeasureRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<MeasureResult> search(String hs6, String direction, String country) {

        var m = table("measures").as("m");
        var mc = table("measure_countries").as("mc");

        var id = field(name("m", "id"), Long.class);
        var goodsCode = field(name("m", "goods_code"), String.class);
        var measureId = field(name("mc", "measure_id"), Long.class);
        var countryCode = field(name("mc", "country_code"), String.class);

        return dsl.select(
                    id,
                    goodsCode,
                    field(name("m", "direction"), String.class),
                    field(name("m", "action"), String.class),
                    field(name("m", "legal_reference"), String.class),
                    field(name("m", "legal_url"), String.class)
                )
                .from(m)
                .join(mc).on(id.eq(measureId))
                .where(goodsCode.between(hs6 + "0000", hs6 + "9999"))
                .and(field(name("m", "direction"), String.class).eq(direction))
                .and(countryCode.eq(country))
                .fetch(r -> new MeasureResult(
                        r.value1(),
                        r.value2(),
                        r.value3(),
                        r.value4(),
                        r.value5(),
                        r.value6(),
                        getCountries(r.value1()),
                        getCategories(r.value1()),
                        getFootnotes(r.value1())
                ));
    }

    private List<String> getCountries(long id) {
        return dsl.select(field("country_code", String.class))
                .from("measure_countries")
                .where(field("measure_id", Long.class).eq(id))
                .fetch(0, String.class);
    }

    private List<String> getCategories(long id) {
        return dsl.select(field("category", String.class))
                .from("measure_categories")
                .where(field("measure_id", Long.class).eq(id))
                .fetch(0, String.class);
    }

    private List<MeasureResult.Footnote> getFootnotes(long id) {

        var f = table("footnotes").as("f");
        var mf = table("measure_footnotes").as("mf");

        return dsl.select(
                    field(name("f", "code"), String.class),
                    field(name("f", "text"), String.class)
                )
                .from(f)
                .join(mf)
                .on(field(name("f", "code"), String.class)
                    .eq(field(name("mf", "footnote_code"), String.class)))
                .where(field(name("mf", "measure_id"), Long.class).eq(id))
                .fetch(r -> new MeasureResult.Footnote(
                        r.value1(),
                        r.value2()
                ));
    }
}