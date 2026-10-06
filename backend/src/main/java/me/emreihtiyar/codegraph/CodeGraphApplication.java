package me.emreihtiyar.codegraph;

import java.util.Arrays;
import me.emreihtiyar.codegraph.extract.java.ParserLocale;
import me.emreihtiyar.codegraph.mcp.StdioBridge;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeGraphApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains(StdioBridge.FLAG)) {
            StdioBridge.main(args);
            return;
        }
        ParserLocale.ensureSafeDefault();
        SpringApplication.run(CodeGraphApplication.class, args);
    }

}
