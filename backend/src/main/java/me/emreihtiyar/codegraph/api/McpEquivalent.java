package me.emreihtiyar.codegraph.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * REST uç noktasının karşılığı olan MCP aracı (PRD §8.7, §8.8). Her {@code /api} uç noktası ya bu ya da
 * {@link RestOnly} ile işaretlenir; parite testi REST ile MCP araç kümesinin örtüştüğünü doğrular.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpEquivalent {

    /** MCP araç adı; {@link ToolNames} sabitlerinden biri. */
    String value();
}
