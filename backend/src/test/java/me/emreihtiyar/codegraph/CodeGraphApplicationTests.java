package me.emreihtiyar.codegraph;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "codegraph.neo4j.data-dir=target/neo4j-test/${random.uuid}")
class CodeGraphApplicationTests {

    @Test
    void contextLoads() {
    }

}
