package me.emreihtiyar.codegraph.config;

import java.nio.file.Path;
import java.util.List;

/** Repo yapılandırma dosyası okunamadığında veya geçersiz olduğunda; tüm hataları birlikte taşır. */
public class ReposConfigException extends RuntimeException {

    private final transient List<String> errors;

    public ReposConfigException(Path source, List<String> errors) {
        super("Geçersiz repo yapılandırması (" + source + "):\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
