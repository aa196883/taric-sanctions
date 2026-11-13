package com.semsoft.taric.service;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(
        name = "taric.goods.auto-import",
        havingValue = "true",
        matchIfMissing = true
)
public class GoodsAutoImport {

    private final GoodsImportService service;

    @Value("${taric.goods.import-file}")
    private String file;

    public GoodsAutoImport(GoodsImportService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void importAfterStartup() throws Exception {

        boolean imported = service.importFile(Path.of(file));

        if (imported) {
            System.out.println("Goods imported successfully");
        } else {
            System.out.println("Goods already imported, skipping");
        }
    }
}