package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;

/**
 * Spring {@code @RequestMapping} ailesi anotasyonlarını okur (PRD FR-02, FR-03). Hem controller'larda hem Feign
 * arayüzlerinde aynı kurallar geçerlidir.
 */
public final class RequestMappings {

    /**
     * @param methods boşsa {@link HttpMethod#ANY}
     * @param paths   en az bir eleman; path verilmemişse boş metin. Sabit olarak değerlendirilemeyen path'ler
     *                kaynak metniyle gelir ve {@code resolved=false} olur.
     */
    public record Mapping(List<HttpMethod> methods, List<String> paths, boolean resolved) {}

    private static final List<String> NAMES =
            List.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    private RequestMappings() {}

    public static boolean has(NodeWithAnnotations<?> node) {
        return JavaNames.hasAnnotation(node, NAMES.toArray(String[]::new));
    }

    public static Optional<Mapping> read(NodeWithAnnotations<?> node, ConstantResolver constants) {
        Optional<AnnotationExpr> found = JavaNames.annotation(node, NAMES.toArray(String[]::new));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AnnotationExpr annotation = found.get();
        String name = JavaNames.simpleName(annotation.getNameAsString());

        List<HttpMethod> methods = new ArrayList<>();
        if (name.equals("RequestMapping")) {
            for (Expression e : JavaNames.attribute(annotation, "method")) {
                String m = JavaNames.simpleName(e.toString()).toUpperCase(Locale.ROOT);
                try {
                    methods.add(HttpMethod.valueOf(m));
                } catch (IllegalArgumentException ignored) {
                    // TRACE vb. desteklenmeyen metotlar atlanır
                }
            }
        } else {
            methods.add(HttpMethod.valueOf(name.substring(0, name.length() - "Mapping".length()).toUpperCase(Locale.ROOT)));
        }
        if (methods.isEmpty()) {
            methods.add(HttpMethod.ANY);
        }

        List<String> paths = new ArrayList<>();
        boolean resolved = true;
        for (Expression e : JavaNames.attribute(annotation, "value", "path")) {
            Optional<String> value = constants.evaluate(e);
            resolved &= value.isPresent() && SpringProperties.isResolved(value.get());
            paths.add(value.orElse(e.toString()));
        }
        if (paths.isEmpty()) {
            paths.add("");
        }
        return Optional.of(new Mapping(List.copyOf(methods), List.copyOf(paths), resolved));
    }
}
