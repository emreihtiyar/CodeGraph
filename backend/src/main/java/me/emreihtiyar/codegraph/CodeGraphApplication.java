package me.emreihtiyar.codegraph;

import me.emreihtiyar.codegraph.extract.java.ParserLocale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeGraphApplication {

    public static void main(String[] args) {
        ParserLocale.ensureSafeDefault();
        SpringApplication.run(CodeGraphApplication.class, args);
    }

}
