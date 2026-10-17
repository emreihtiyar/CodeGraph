package me.emreihtiyar.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceTextTest {

    @Test
    void stripsUtf8Bom() {
        byte[] bytes = "﻿package a;\r\n// ğüşıöç".getBytes(StandardCharsets.UTF_8);
        assertThat(SourceText.decode(bytes)).isEqualTo("package a;\r\n// ğüşıöç");
    }

    @Test
    void decodesUtf16WithBom() {
        byte[] bytes = "﻿const x = 'ş';".getBytes(StandardCharsets.UTF_16LE);
        assertThat(SourceText.decode(bytes)).isEqualTo("const x = 'ş';");
    }

    @Test
    void fallsBackToWindows1254ForAnsiFiles() {
        byte[] bytes = "// Müşteri kaydı İşlem".getBytes(SourceText.FALLBACK);
        assertThat(SourceText.decode(bytes)).isEqualTo("// Müşteri kaydı İşlem");
    }
}
