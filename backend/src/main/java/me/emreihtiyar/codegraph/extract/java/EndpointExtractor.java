package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.PathNormalizer;
import me.emreihtiyar.codegraph.extract.model.EndpointFact;
import me.emreihtiyar.codegraph.extract.model.HttpMethod;

/**
 * Spring controller endpoint'leri (PRD FR-02): context-path + sınıf + metot mapping'i birleştirilir. Mapping
 * anotasyonları controller'ın uyguladığı proje arayüzlerinden de miras alınabilir (ör. API-first arayüzler).
 */
public final class EndpointExtractor {

    public void extract(JavaContext ctx) {
        String contextPath = ctx.facts().service().contextPath();
        Set<String> seen = new HashSet<>();
        for (JavaSourceSet.JavaFile file : ctx.sources().files()) {
            for (ClassOrInterfaceDeclaration cls : file.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (cls.isInterface() || !JavaNames.hasAnnotation(cls, "RestController", "Controller")) {
                    continue;
                }
                List<ClassOrInterfaceDeclaration> interfaces = projectInterfaces(ctx, cls);
                RequestMappings.Mapping classMapping = RequestMappings.read(cls, ctx.constants())
                        .or(() -> interfaces.stream()
                                .map(i -> RequestMappings.read(i, ctx.constants()))
                                .flatMap(Optional::stream)
                                .findFirst())
                        .orElse(null);
                List<String> classPaths = classMapping == null ? List.of("") : classMapping.paths();
                String classFqn = JavaNames.classFqn(cls);

                for (MethodDeclaration method : cls.getMethods()) {
                    Optional<RequestMappings.Mapping> mapping = RequestMappings.read(method, ctx.constants())
                            .or(() -> inheritedMapping(ctx, interfaces, method));
                    if (mapping.isEmpty()) {
                        continue;
                    }
                    if (!mapping.get().resolved() || (classMapping != null && !classMapping.resolved())) {
                        ctx.issue(file, method, "Endpoint path'i sabit olarak çözülemedi: " + mapping.get().paths());
                    }
                    String handler = JavaNames.methodFqn(classFqn, method);
                    for (String classPath : classPaths) {
                        for (String methodPath : mapping.get().paths()) {
                            String path = PathNormalizer.join(contextPath, classPath, methodPath);
                            for (HttpMethod http : mapping.get().methods()) {
                                String normalized = PathNormalizer.normalize(path);
                                if (seen.add(http + " " + normalized)) {
                                    ctx.facts().endpoints().add(new EndpointFact(http, path, normalized, handler));
                                } else {
                                    ctx.issue(file, method, "Aynı endpoint birden çok kez tanımlı: " + http + " " + path);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static Optional<RequestMappings.Mapping> inheritedMapping(
            JavaContext ctx, List<ClassOrInterfaceDeclaration> interfaces, MethodDeclaration method) {
        for (ClassOrInterfaceDeclaration api : interfaces) {
            for (MethodDeclaration candidate : api.getMethodsByName(method.getNameAsString())) {
                if (candidate.getParameters().size() == method.getParameters().size()) {
                    Optional<RequestMappings.Mapping> mapping = RequestMappings.read(candidate, ctx.constants());
                    if (mapping.isPresent()) {
                        return mapping;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Sınıfın (dolaylı olarak) uyguladığı, bu repoda tanımlı arayüzler. */
    static List<ClassOrInterfaceDeclaration> projectInterfaces(JavaContext ctx, ClassOrInterfaceDeclaration cls) {
        List<ClassOrInterfaceDeclaration> result = new ArrayList<>();
        collectInterfaces(ctx, cls, result, new HashSet<>());
        return result;
    }

    private static void collectInterfaces(
            JavaContext ctx, ClassOrInterfaceDeclaration type, List<ClassOrInterfaceDeclaration> out, Set<String> seen) {
        List<ClassOrInterfaceType> supers = new ArrayList<>(type.getImplementedTypes());
        if (type.isInterface()) {
            supers.addAll(type.getExtendedTypes());
        }
        for (ClassOrInterfaceType st : supers) {
            type.findCompilationUnit()
                    .flatMap(cu -> ctx.sources().resolveTypeName(cu, st.getNameWithScope()))
                    .filter(seen::add)
                    .flatMap(fqn -> ctx.sources().type(fqn))
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration c && c.isInterface())
                    .map(t -> (ClassOrInterfaceDeclaration) t)
                    .ifPresent(i -> {
                        out.add(i);
                        collectInterfaces(ctx, i, out, seen);
                    });
        }
    }
}
