package com.semsoft.taric.service;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "taric.auto-import",
        havingValue = "true",
        matchIfMissing = true
)
public class TaricAutoImport {

    private final GoodsImportService goodsService;
    private final MeasuresImportService measuresService;

    @Value("${taric.goods.import-file}")
    private String goodsFile;

    @Value("${taric.measures.import-file}")
    private String importFile;

    @Value("${taric.measures.export-file}")
    private String exportFile;

    public TaricAutoImport(
            GoodsImportService goodsService,
            MeasuresImportService measuresService) {

        this.goodsService = goodsService;
        this.measuresService = measuresService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importAfterStartup() throws Exception {

        boolean goodsImported = goodsService.importFile(
                Path.of(goodsFile)
        );

        boolean measuresImported = measuresService.importFiles(
                Path.of(importFile),
                Path.of(exportFile)
        );

        System.out.println("Goods imported: " + goodsImported);
        System.out.println("Measures imported: " + measuresImported);
    }
}