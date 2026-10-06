package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Geçerli repo yapılandırması: {@code codegraph.repos} doluysa satır içi tanımlar, değilse
 * {@code codegraph.repos-file}. Her analizde yeniden okunur; dosya değişiklikleri uygulama yeniden başlatılmadan
 * etkili olur.
 */
@Component
public class ReposConfigProvider {

    private final CodeGraphProperties properties;
    private final ReposConfigLoader loader;

    public ReposConfigProvider(CodeGraphProperties properties, ReposConfigLoader loader) {
        this.properties = properties;
        this.loader = loader;
    }

    /** @throws ReposConfigException yapılandırma eksik veya geçersizse */
    public ReposConfig load() {
        if (!properties.repos().isEmpty()) {
            return loader.fromInline(properties.repos(), properties.placeholders(), Path.of(""));
        }
        ReposConfig file = loader.load(properties.reposFile());
        if (properties.placeholders().isEmpty()) {
            return file;
        }
        Map<String, String> placeholders = new LinkedHashMap<>(file.placeholders());
        placeholders.putAll(properties.placeholders());
        return new ReposConfig(file.source(), file.repos(), placeholders);
    }
}
