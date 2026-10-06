package me.emreihtiyar.codegraph.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** MCP'ye bilerek açılmayan REST uç noktası (ör. yazma işlemleri; MCP araçları salt-okunurdur). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RestOnly {

    /** Neden MCP karşılığı olmadığı. */
    String value();
}
