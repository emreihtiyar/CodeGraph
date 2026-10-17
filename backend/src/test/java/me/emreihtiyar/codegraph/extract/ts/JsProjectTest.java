package me.emreihtiyar.codegraph.extract.ts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsProjectTest {

    private static JsModule module(String path, String source) {
        return new JsModule(path, source, JsLowering.program(TsSourceParser.parse(source, true).program()));
    }

    @Test
    void importWithDifferentCaseResolvesLikeOnWindows() {
        JsModule screen = module("src/screens/Home.tsx", "import Button from '../components/button';");
        JsModule button = module("src/components/Button.tsx", "export default function Button() { return null; }");
        JsModule other = module("src/components/Other.tsx", "export const x = 1;");
        JsProject project = new JsProject(List.of(screen, button, other), PathAliases.none());

        assertThat(project.resolveModule(screen, "../components/button")).contains(button);
        assertThat(project.resolveModule(screen, "../components/Other")).contains(other);
        assertThat(project.resolveModule(screen, "../components/missing")).isEmpty();
    }
}
