package com.semsoft.taric.model;

import java.util.List;

public record MeasureResult(
        long id,
        String goodsCode,
        String direction,
        String measureTypeCode,
        String measureType,
        String legalReference,
        String legalUrl,
        List<String> countries,
        List<String> categories,
        List<Footnote> footnotes
) {
    public record Footnote(String code, String text) {}
}