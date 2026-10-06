package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.ClassFact;
import me.emreihtiyar.codegraph.extract.model.ClassKind;
import me.emreihtiyar.codegraph.extract.model.FeignClientFact;
import me.emreihtiyar.codegraph.extract.model.JpaRepositoryFact;
import me.emreihtiyar.codegraph.extract.model.MethodFact;
import me.emreihtiyar.codegraph.extract.model.OverrideFact;
import me.emreihtiyar.codegraph.extract.model.RepositoryMethodFact;
import me.emreihtiyar.codegraph.extract.model.Visibility;

/**
 * Sınıflar, tüm metotlar (görünürlükten bağımsız) ve {@code OVERRIDES} ilişkileri (PRD FR-04, FR-05); ayrıca çağrı
 * çözümlemesi için {@link TypeModel}.
 *
 * <p>Lombok ({@code @Data}, {@code @Getter}, {@code @Setter}, {@code @Value}, {@code @Builder}) ve record
 * accessor'ları kaynakta olmadığından sentetik metot olarak eklenir (PRD §11.1). Feign arayüzleri ve Spring Data
 * repository'leri {@code Class}/{@code Method} değil, kendi düğüm tipleriyle temsil edilir; modelde yer alırlar.
 * Constructor'lar metot düğümü olarak modellenmez.
 */
public final class ClassMethodExtractor {

    /**
     * @param externalModel LIBRARY repolarının modeli; üst tip ve çağrı hedefi çözümü için
     * @return bu reponun tip modeli
     */
    public TypeModel extract(JavaContext ctx, TypeModel externalModel) {
        String repo = ctx.facts().repoName();
        Set<String> feignClients = new HashSet<>();
        ctx.facts().feignClients().stream().map(FeignClientFact::fqn).forEach(feignClients::add);
        Set<String> repositories = new HashSet<>();
        ctx.facts().jpaRepositories().stream().map(JpaRepositoryFact::fqn).forEach(repositories::add);

        TypeModel model = new TypeModel();
        for (JavaSourceSet.JavaFile file : ctx.sources().files()) {
            for (TypeDeclaration<?> type : file.cu().findAll(TypeDeclaration.class)) {
                Optional<String> fqnOpt = type.getFullyQualifiedName();
                if (fqnOpt.isEmpty()) {
                    continue;
                }
                String fqn = fqnOpt.get();
                CompilationUnit cu = file.cu();
                TypeModel.Kind kind = kind(type, feignClients.contains(fqn), repositories.contains(fqn));

                List<String> superTypes = new ArrayList<>();
                boolean externalSuper = false;
                for (ClassOrInterfaceType st : superTypes(type)) {
                    Optional<String> resolved = ctx.sources().resolveTypeName(cu, st.getNameWithScope());
                    resolved.ifPresent(superTypes::add);
                    if (resolved.isEmpty()
                            || !ctx.sources().isProjectType(resolved.get()) && !externalModel.contains(resolved.get())) {
                        externalSuper = true;
                    }
                }

                Map<String, String> fieldTypes = new LinkedHashMap<>();
                for (FieldDeclaration field : type.getFields()) {
                    for (VariableDeclarator v : field.getVariables()) {
                        fieldTypes.put(v.getNameAsString(), typeName(ctx, cu, v.getType()));
                    }
                }
                if (type instanceof RecordDeclaration record) {
                    record.getParameters().forEach(p -> fieldTypes.put(p.getNameAsString(), typeName(ctx, cu, p.getType())));
                }

                List<TypeModel.MethodInfo> methods = new ArrayList<>();
                boolean isClassLike = kind != TypeModel.Kind.FEIGN_CLIENT && kind != TypeModel.Kind.REPOSITORY;
                for (MethodDeclaration method : type.getMethods()) {
                    String methodFqn = JavaNames.methodFqn(fqn, method);
                    boolean isAbstract = method.isAbstract()
                            || (method.getBody().isEmpty() && !method.isStatic() && !method.isDefault());
                    methods.add(new TypeModel.MethodInfo(
                            methodFqn,
                            fqn,
                            method.getNameAsString(),
                            JavaNames.parameterTypes(method),
                            method.getParameters().size(),
                            method.getParameters().stream().anyMatch(Parameter::isVarArgs),
                            typeName(ctx, cu, method.getType()),
                            isAbstract,
                            method.isStatic(),
                            JavaNames.hasAnnotation(method, "Async")));
                    if (isClassLike) {
                        ctx.facts().methods().add(new MethodFact(
                                methodFqn,
                                fqn,
                                method.getNameAsString(),
                                method.getDeclarationAsString(false, false, true),
                                visibility(type, method),
                                method.isStatic(),
                                isAbstract,
                                isAccessor(method),
                                false,
                                ctx.sourceRef(method, file),
                                ctx.code(method, file)));
                    }
                }
                if (kind == TypeModel.Kind.REPOSITORY) {
                    for (RepositoryMethodFact rm : ctx.facts().repositoryMethods()) {
                        if (rm.repositoryFqn().equals(fqn) && rm.inherited()) {
                            methods.add(new TypeModel.MethodInfo(
                                    rm.fqn(), fqn, rm.name(), "*", -1, false, null, false, false, false));
                        }
                    }
                }
                if (isClassLike) {
                    syntheticAccessors(ctx, file, type, fqn, fieldTypes, methods);
                    ctx.facts().classes().add(new ClassFact(
                            fqn,
                            classKind(type),
                            kind == TypeModel.Kind.INTERFACE,
                            kind == TypeModel.Kind.ABSTRACT_CLASS,
                            superTypes.stream().filter(s -> !isInterfaceType(ctx, externalModel, s)).findFirst()
                                    .orElse(null),
                            superTypes.stream().filter(s -> isInterfaceType(ctx, externalModel, s)).toList(),
                            ctx.sourceRef(type, file)));
                }
                model.add(new TypeModel.TypeInfo(
                        repo, fqn, kind, superTypes, externalSuper, methods, fieldTypes, beanName(type),
                        JavaNames.hasAnnotation(type, "Primary")));
            }
        }
        overrides(ctx, model, externalModel);
        return model;
    }

    /** Alt sınıf/implementasyon metodu → üst tipteki aynı imzalı metot (generic'lerde ad + parametre sayısı). */
    private static void overrides(JavaContext ctx, TypeModel model, TypeModel externalModel) {
        TypeModel all = new TypeModel();
        all.addAll(externalModel);
        all.addAll(model);
        for (TypeModel.TypeInfo type : model.types()) {
            if (type.kind() == TypeModel.Kind.FEIGN_CLIENT || type.kind() == TypeModel.Kind.REPOSITORY) {
                continue;
            }
            List<TypeModel.TypeInfo> ancestors = all.hierarchy(type.fqn());
            for (TypeModel.MethodInfo method : type.methods()) {
                if (method.isStatic() || method.paramCount() < 0) {
                    continue;
                }
                for (TypeModel.TypeInfo ancestor : ancestors.subList(1, ancestors.size())) {
                    if (ancestor.kind() == TypeModel.Kind.FEIGN_CLIENT || ancestor.kind() == TypeModel.Kind.REPOSITORY) {
                        continue;
                    }
                    overridden(ancestor, method).ifPresent(o -> ctx.facts().overrides()
                            .add(new OverrideFact(method.key(), o.key())));
                }
            }
        }
    }

    private static Optional<TypeModel.MethodInfo> overridden(TypeModel.TypeInfo ancestor, TypeModel.MethodInfo method) {
        List<TypeModel.MethodInfo> sameName = ancestor.methods().stream()
                .filter(m -> m.name().equals(method.name()) && !m.isStatic())
                .toList();
        Optional<TypeModel.MethodInfo> exact = sameName.stream()
                .filter(m -> m.signatureKey().equals(method.signatureKey()))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        List<TypeModel.MethodInfo> sameArity = sameName.stream()
                .filter(m -> m.paramCount() == method.paramCount())
                .toList();
        return sameArity.size() == 1 ? Optional.of(sameArity.get(0)) : Optional.empty();
    }

    private static void syntheticAccessors(
            JavaContext ctx,
            JavaSourceSet.JavaFile file,
            TypeDeclaration<?> type,
            String fqn,
            Map<String, String> fieldTypes,
            List<TypeModel.MethodInfo> methods) {
        Set<String> existing = new HashSet<>();
        methods.forEach(m -> existing.add(m.signatureKey()));

        if (type instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                addSynthetic(ctx, file, component, fqn, component.getNameAsString(), "",
                        fieldTypes.get(component.getNameAsString()), JavaNames.simpleTypeName(component.getType()),
                        false, existing, methods);
            }
            return;
        }
        boolean classData = JavaNames.hasAnnotation(type, "Data");
        boolean classValue = JavaNames.hasAnnotation(type, "Value");
        boolean classGetter = classData || classValue || JavaNames.hasAnnotation(type, "Getter");
        boolean classSetter = classData || JavaNames.hasAnnotation(type, "Setter");
        if (JavaNames.hasAnnotation(type, "Builder", "SuperBuilder")) {
            addSynthetic(ctx, file, type, fqn, "builder", "", fqn + "Builder", fqn + "Builder", true, existing, methods);
        }
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            boolean getter = classGetter || JavaNames.hasAnnotation(field, "Getter");
            boolean setter = (classSetter || JavaNames.hasAnnotation(field, "Setter")) && !field.isFinal() && !classValue;
            for (VariableDeclarator v : field.getVariables()) {
                String name = v.getNameAsString();
                String simpleType = JavaNames.simpleTypeName(v.getType());
                boolean primitiveBoolean = v.getType().isPrimitiveType() && simpleType.equals("boolean");
                String property = primitiveBoolean && name.matches("is[A-Z].*") ? name.substring(2) : name;
                String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
                if (getter) {
                    String getterName = (primitiveBoolean ? "is" : "get") + cap;
                    addSynthetic(ctx, file, field, fqn, getterName, "", fieldTypes.get(name), simpleType + " " + getterName
                            + "()", false, existing, methods);
                }
                if (setter) {
                    addSynthetic(ctx, file, field, fqn, "set" + cap, simpleType, "void", "void set" + cap + "("
                            + simpleType + " " + property + ")", false, existing, methods);
                }
            }
        }
    }

    private static void addSynthetic(
            JavaContext ctx,
            JavaSourceSet.JavaFile file,
            com.github.javaparser.ast.Node origin,
            String classFqn,
            String name,
            String parameterTypes,
            String returnType,
            String signature,
            boolean isStatic,
            Set<String> existing,
            List<TypeModel.MethodInfo> methods) {
        String signatureKey = name + "(" + parameterTypes + ")";
        if (!existing.add(signatureKey)) {
            return;
        }
        String methodFqn = classFqn + "#" + signatureKey;
        int paramCount = parameterTypes.isEmpty() ? 0 : parameterTypes.split(",").length;
        methods.add(new TypeModel.MethodInfo(
                methodFqn, classFqn, name, parameterTypes, paramCount, false, returnType, false, isStatic, false));
        ctx.facts().methods().add(new MethodFact(
                methodFqn, classFqn, name, signature.contains("(") ? signature : signature + " " + name + "()",
                Visibility.PUBLIC, isStatic, false, !name.equals("builder"), true,
                ctx.sourceRef(origin, file), ctx.code(origin, file)));
    }

    /** Basit getter/setter: tek {@code return alan;} ya da tek {@code this.alan = param;} ifadesi. */
    static boolean isAccessor(MethodDeclaration method) {
        if (method.getBody().isEmpty() || method.isStatic()) {
            return false;
        }
        List<Statement> statements = method.getBody().get().getStatements();
        if (statements.size() != 1) {
            return false;
        }
        Statement stmt = statements.get(0);
        String name = method.getNameAsString();
        if (method.getParameters().isEmpty() && name.matches("(get|is)[A-Z].*") && stmt instanceof ReturnStmt ret) {
            return ret.getExpression().map(ClassMethodExtractor::isFieldRef).orElse(false);
        }
        if (method.getParameters().size() == 1 && name.matches("set[A-Z].*") && stmt instanceof ExpressionStmt es
                && es.getExpression() instanceof AssignExpr assign
                && assign.getOperator() == AssignExpr.Operator.ASSIGN) {
            return isFieldRef(assign.getTarget()) && assign.getValue() instanceof NameExpr;
        }
        return false;
    }

    private static boolean isFieldRef(Expression e) {
        return e instanceof NameExpr || e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr;
    }

    private static Visibility visibility(TypeDeclaration<?> type, CallableDeclaration<?> method) {
        if (type instanceof ClassOrInterfaceDeclaration c && c.isInterface() && !method.isPrivate()) {
            return Visibility.PUBLIC;
        }
        return switch (method.getAccessSpecifier()) {
            case PUBLIC -> Visibility.PUBLIC;
            case PROTECTED -> Visibility.PROTECTED;
            case PRIVATE -> Visibility.PRIVATE;
            case NONE -> Visibility.PACKAGE;
        };
    }

    private static TypeModel.Kind kind(TypeDeclaration<?> type, boolean feign, boolean repository) {
        if (feign) {
            return TypeModel.Kind.FEIGN_CLIENT;
        }
        if (repository) {
            return TypeModel.Kind.REPOSITORY;
        }
        if (type instanceof ClassOrInterfaceDeclaration c) {
            if (c.isInterface()) {
                return TypeModel.Kind.INTERFACE;
            }
            return c.isAbstract() ? TypeModel.Kind.ABSTRACT_CLASS : TypeModel.Kind.CLASS;
        }
        if (type instanceof EnumDeclaration) {
            return TypeModel.Kind.ENUM;
        }
        if (type instanceof RecordDeclaration) {
            return TypeModel.Kind.RECORD;
        }
        return TypeModel.Kind.CLASS;
    }

    private static ClassKind classKind(TypeDeclaration<?> type) {
        if (JavaNames.hasAnnotation(type, "RestController", "Controller")) {
            return ClassKind.CONTROLLER;
        }
        if (JavaNames.hasAnnotation(type, "Service")) {
            return ClassKind.SERVICE;
        }
        if (JavaNames.hasAnnotation(type, "Component", "Repository", "Configuration")) {
            return ClassKind.COMPONENT;
        }
        return ClassKind.OTHER;
    }

    private static List<ClassOrInterfaceType> superTypes(TypeDeclaration<?> type) {
        List<ClassOrInterfaceType> result = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration c) {
            result.addAll(c.getExtendedTypes());
            result.addAll(c.getImplementedTypes());
        } else if (type instanceof EnumDeclaration e) {
            result.addAll(e.getImplementedTypes());
        } else if (type instanceof RecordDeclaration r) {
            result.addAll(r.getImplementedTypes());
        }
        return result;
    }

    private static boolean isInterfaceType(JavaContext ctx, TypeModel externalModel, String fqn) {
        return ctx.sources().type(fqn)
                .map(t -> t instanceof ClassOrInterfaceDeclaration c && c.isInterface())
                .orElseGet(() -> externalModel.type(fqn)
                        .map(t -> t.kind() == TypeModel.Kind.INTERFACE || t.kind() == TypeModel.Kind.FEIGN_CLIENT
                                || t.kind() == TypeModel.Kind.REPOSITORY)
                        .orElse(false));
    }

    /** Spring bean adı: stereotip anotasyonunun değeri ya da küçük harfle başlayan sınıf adı. */
    private static String beanName(TypeDeclaration<?> type) {
        for (String stereotype : List.of("Component", "Service", "Repository", "Controller", "RestController",
                "Configuration")) {
            var annotation = JavaNames.annotation(type, stereotype);
            if (annotation.isPresent()) {
                return JavaNames.attribute(annotation.get(), "value").stream()
                        .findFirst()
                        .map(e -> e.toString().replaceAll("^\"|\"$", ""))
                        .orElseGet(() -> Character.toLowerCase(type.getNameAsString().charAt(0))
                                + type.getNameAsString().substring(1));
            }
        }
        return null;
    }

    /** Tipin FQN'i (çözülebildiyse), aksi halde basit adı. */
    static String typeName(JavaContext ctx, CompilationUnit cu, Type type) {
        String simple = JavaNames.simpleTypeName(type);
        if (type.isPrimitiveType() || type.isVoidType() || simple.endsWith("[]")) {
            return simple;
        }
        String name = type instanceof ClassOrInterfaceType cit ? cit.getNameWithScope() : simple;
        return ctx.sources().resolveTypeName(cu, name).orElse(simple);
    }
}
