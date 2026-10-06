package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.PathNormalizer;
import me.emreihtiyar.codegraph.extract.model.FeignClientFact;
import me.emreihtiyar.codegraph.extract.model.FeignMethodFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;

/**
 * {@code @FeignClient} arayüzleri (PRD FR-03). Hedef servis adı {@code name}/{@code value} niteliğinden,
 * placeholder'lar application dosyalarından çözülerek okunur. Proje içindeki üst arayüzlerden miras alınan metotlar
 * da Feign metodu sayılır. Metot belirtilmemiş {@code @RequestMapping} OpenFeign'deki gibi GET kabul edilir.
 */
public final class FeignExtractor {

    public void extract(JavaContext ctx) {
        for (JavaSourceSet.JavaFile file : ctx.sources().files()) {
            for (ClassOrInterfaceDeclaration api : file.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<AnnotationExpr> feign = JavaNames.annotation(api, "FeignClient");
                if (!api.isInterface() || feign.isEmpty()) {
                    continue;
                }
                String clientFqn = JavaNames.classFqn(api);
                List<Expression> nameExpr = JavaNames.attribute(feign.get(), "name", "value");
                String rawName = nameExpr.isEmpty() ? null : nameExpr.get(0).toString().replaceAll("^\"|\"$", "");
                String targetName = nameExpr.isEmpty()
                        ? null
                        : ctx.constants().evaluate(nameExpr.get(0)).filter(SpringProperties::isResolved).orElse(null);
                if (targetName == null) {
                    ctx.issue(file, api, "Feign hedef servis adı çözülemedi: " + rawName);
                }
                List<Expression> pathExpr = JavaNames.attribute(feign.get(), "path");
                String clientPath = pathExpr.isEmpty() ? "" : ctx.constants().evaluate(pathExpr.get(0)).orElse(null);
                if (clientPath == null || !SpringProperties.isResolved(clientPath)) {
                    ctx.issue(file, api, "Feign path niteliği çözülemedi: " + pathExpr);
                    clientPath = clientPath == null ? pathExpr.get(0).toString() : clientPath;
                }
                ctx.facts().feignClients().add(new FeignClientFact(
                        clientFqn, rawName, targetName, clientPath, ctx.sourceRef(api, file)));

                for (MethodDeclaration method : methods(ctx, api)) {
                    Optional<RequestMappings.Mapping> mapping = RequestMappings.read(method, ctx.constants());
                    if (mapping.isEmpty()) {
                        continue;
                    }
                    if (!mapping.get().resolved()) {
                        ctx.issue(file, method, "Feign metot path'i çözülemedi: " + mapping.get().paths());
                    }
                    HttpMethod http = mapping.get().methods().get(0) == HttpMethod.ANY
                            ? HttpMethod.GET
                            : mapping.get().methods().get(0);
                    String path = PathNormalizer.join(clientPath, mapping.get().paths().get(0));
                    JavaSourceSet.JavaFile methodFile = method.findCompilationUnit()
                            .flatMap(cu -> ctx.sources().files().stream().filter(f -> f.cu() == cu).findFirst())
                            .orElse(file);
                    ctx.facts().feignMethods().add(new FeignMethodFact(
                            JavaNames.methodFqn(clientFqn, method),
                            clientFqn,
                            http,
                            path,
                            PathNormalizer.normalize(path),
                            ctx.sourceRef(method, methodFile),
                            ctx.code(method, methodFile)));
                }
            }
        }
    }

    /** Arayüzün kendi metotları + proje içindeki üst arayüzlerden miras alınanlar (aynı imza bir kez). */
    private static List<MethodDeclaration> methods(JavaContext ctx, ClassOrInterfaceDeclaration api) {
        List<MethodDeclaration> result = new ArrayList<>(api.getMethods());
        Set<String> signatures = new HashSet<>();
        result.forEach(m -> signatures.add(m.getNameAsString() + "(" + JavaNames.parameterTypes(m) + ")"));
        for (ClassOrInterfaceDeclaration parent : EndpointExtractor.projectInterfaces(ctx, api)) {
            for (MethodDeclaration m : parent.getMethods()) {
                if (signatures.add(m.getNameAsString() + "(" + JavaNames.parameterTypes(m) + ")")) {
                    result.add(m);
                }
            }
        }
        return result;
    }
}
