package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import me.emreihtiyar.codegraph.extract.model.AccessType;
import me.emreihtiyar.codegraph.extract.model.EntityFact;
import me.emreihtiyar.codegraph.extract.model.EntityRelationFact;
import me.emreihtiyar.codegraph.extract.model.EntityRelationType;
import me.emreihtiyar.codegraph.extract.model.JpaRepositoryFact;
import me.emreihtiyar.codegraph.extract.model.RepositoryMethodFact;

/**
 * JPA entity'leri, tablo eşlemeleri, entity ilişkileri ve Spring Data repository'leri (PRD FR-06).
 *
 * <p>Repository metotları READ/WRITE olarak sınıflandırılır; Spring Data temel arayüzlerinden miras alınan
 * metotlar ({@code save}, {@code findById} ...) {@code Repo#ad} anahtarıyla sentetik olarak eklenir. Çağrı
 * çözümlemesi bu metotları isimle bulur, çünkü Spring Data sınıf yolunda değildir.
 */
public final class JpaExtractor {

    private static final Set<String> CRUD = Set.of(
            "save", "saveAll", "findById", "existsById", "findAll", "findAllById", "count", "deleteById", "delete",
            "deleteAllById", "deleteAll");
    private static final Set<String> JPA = Set.of(
            "flush", "saveAndFlush", "saveAllAndFlush", "deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch",
            "getReferenceById", "getById", "getOne");
    private static final Set<String> EXECUTORS = Set.of("findOne", "findAll", "count", "exists", "findBy");

    /** Temel arayüz → miras aldığı metotlar. {@code Repository} işaret arayüzüdür, metodu yoktur. */
    private static final Map<String, Set<String>> BASES = Map.of(
            "Repository", Set.of(),
            "CrudRepository", CRUD,
            "ListCrudRepository", CRUD,
            "PagingAndSortingRepository", Set.of("findAll"),
            "ListPagingAndSortingRepository", Set.of("findAll"),
            "JpaRepository", union(CRUD, JPA),
            "JpaSpecificationExecutor", EXECUTORS,
            "QueryByExampleExecutor", EXECUTORS);
    private static final Set<String> ENTITY_BASES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository");

    private static final Pattern WRITE_NAME =
            Pattern.compile("^(save|delete|remove|insert|update|persist|merge|flush|deleteAll).*");
    private static final Pattern WRITE_QUERY =
            Pattern.compile("^\\s*(insert|update|delete|merge)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Map<String, EntityRelationType> RELATIONS = Map.of(
            "OneToOne", EntityRelationType.ONE_TO_ONE,
            "OneToMany", EntityRelationType.ONE_TO_MANY,
            "ManyToOne", EntityRelationType.MANY_TO_ONE,
            "ManyToMany", EntityRelationType.MANY_TO_MANY);

    public void extract(JavaContext ctx) {
        for (JavaSourceSet.JavaFile file : ctx.sources().files()) {
            for (ClassOrInterfaceDeclaration type : file.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.isInterface() && JavaNames.hasAnnotation(type, "Entity")) {
                    entity(ctx, file, type);
                } else if (type.isInterface()) {
                    repository(ctx, file, type);
                }
            }
        }
    }

    private void entity(JavaContext ctx, JavaSourceSet.JavaFile file, ClassOrInterfaceDeclaration type) {
        String fqn = JavaNames.classFqn(type);
        Optional<AnnotationExpr> table = JavaNames.annotation(type, "Table");
        String tableName = table.flatMap(t -> stringAttribute(ctx, t, "name")).orElse(null);
        String schema = table.flatMap(t -> stringAttribute(ctx, t, "schema")).orElse(null);
        if (tableName == null) {
            String entityName = JavaNames.annotation(type, "Entity")
                    .flatMap(e -> stringAttribute(ctx, e, "name"))
                    .orElse(type.getNameAsString());
            tableName = snakeCase(entityName);
        }
        ctx.facts().entities().add(new EntityFact(fqn, tableName, schema, ctx.sourceRef(type, file), ctx.code(type, file)));

        for (FieldDeclaration field : type.getFields()) {
            for (var relation : RELATIONS.entrySet()) {
                Optional<AnnotationExpr> annotation = JavaNames.annotation(field, relation.getKey());
                if (annotation.isEmpty()) {
                    continue;
                }
                Optional<String> target = targetEntity(ctx, file.cu(), annotation.get(), field.getElementType());
                if (target.isEmpty()) {
                    ctx.issue(file, field, "İlişki hedef entity'si çözülemedi: " + field.getElementType());
                    continue;
                }
                field.getVariables().forEach(v -> ctx.facts().entityRelations().add(
                        new EntityRelationFact(fqn, target.get(), relation.getValue(), v.getNameAsString())));
            }
        }
    }

    /** {@code targetEntity = X.class} → koleksiyonun eleman tipi (Map'te değer) → alan tipi. */
    private static Optional<String> targetEntity(
            JavaContext ctx, CompilationUnit cu, AnnotationExpr annotation, Type fieldType) {
        for (Expression e : JavaNames.attribute(annotation, "targetEntity")) {
            if (e instanceof ClassExpr cls) {
                return ctx.sources().resolveTypeName(cu, JavaNames.simpleTypeName(cls.getType()));
            }
        }
        Type type = fieldType;
        if (type instanceof ClassOrInterfaceType cit && cit.getTypeArguments().isPresent()
                && !cit.getTypeArguments().get().isEmpty()) {
            var args = cit.getTypeArguments().get();
            type = args.get(args.size() - 1);
        }
        return ctx.sources().resolveTypeName(cu, JavaNames.simpleTypeName(type));
    }

    private void repository(JavaContext ctx, JavaSourceSet.JavaFile file, ClassOrInterfaceDeclaration type) {
        Set<String> bases = new LinkedHashSet<>();
        Optional<String> entity = repositoryInfo(ctx, type, bases, new HashSet<>());
        if (bases.isEmpty()) {
            return;
        }
        String fqn = JavaNames.classFqn(type);
        if (entity.isEmpty()) {
            ctx.issue(file, type, "Repository entity tipi çözülemedi");
        }
        ctx.facts().jpaRepositories().add(new JpaRepositoryFact(fqn, entity.orElse(null), ctx.sourceRef(type, file)));

        Set<String> declared = new HashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            declared.add(method.getNameAsString());
            String query = JavaNames.annotation(method, "Query")
                    .flatMap(q -> JavaNames.attribute(q, "value").stream().findFirst())
                    .map(e -> ctx.constants().evaluate(e).orElse(e.toString()))
                    .orElse(null);
            boolean modifying = JavaNames.hasAnnotation(method, "Modifying");
            AccessType access = modifying || (query != null && WRITE_QUERY.matcher(query).matches())
                            || (query == null && WRITE_NAME.matcher(method.getNameAsString()).matches())
                    ? AccessType.WRITE
                    : AccessType.READ;
            ctx.facts().repositoryMethods().add(new RepositoryMethodFact(
                    JavaNames.methodFqn(fqn, method), fqn, method.getNameAsString(), access, query, false));
        }
        Set<String> inherited = new java.util.TreeSet<>();
        bases.forEach(b -> inherited.addAll(BASES.getOrDefault(b, Set.of())));
        for (String name : inherited) {
            if (!declared.contains(name)) {
                AccessType access = WRITE_NAME.matcher(name).matches() ? AccessType.WRITE : AccessType.READ;
                ctx.facts().repositoryMethods().add(
                        new RepositoryMethodFact(inheritedFqn(fqn, name), fqn, name, access, null, true));
            }
        }
    }

    /** Miras alınan (kaynakta olmayan) repository metodunun anahtarı; overload'lar tek düğümde birleşir. */
    public static String inheritedFqn(String repositoryFqn, String name) {
        return repositoryFqn + "#" + name;
    }

    /**
     * Spring Data temel arayüzlerini (doğrudan veya proje içi ara arayüzler üzerinden) toplar ve entity tipini döner.
     * Entity tipi, temel arayüzün ya da ara arayüzün ilk tip argümanıdır.
     */
    private static Optional<String> repositoryInfo(
            JavaContext ctx, ClassOrInterfaceDeclaration type, Set<String> bases, Set<String> visited) {
        if (!visited.add(JavaNames.classFqn(type))) {
            return Optional.empty();
        }
        CompilationUnit cu = type.findCompilationUnit().orElseThrow();
        Optional<String> entity = Optional.empty();
        for (ClassOrInterfaceType parent : type.getExtendedTypes()) {
            String name = parent.getNameAsString();
            Optional<String> firstArg = parent.getTypeArguments()
                    .filter(args -> !args.isEmpty())
                    .flatMap(args -> ctx.sources().resolveTypeName(cu, JavaNames.simpleTypeName(args.get(0))));
            if (BASES.containsKey(name)) {
                bases.add(name);
                if (ENTITY_BASES.contains(name) && entity.isEmpty()) {
                    entity = firstArg;
                }
                continue;
            }
            Optional<ClassOrInterfaceDeclaration> projectParent = ctx.sources()
                    .resolveTypeName(cu, parent.getNameWithScope())
                    .flatMap(ctx.sources()::type)
                    .filter(t -> t instanceof ClassOrInterfaceDeclaration c && c.isInterface())
                    .map(t -> (ClassOrInterfaceDeclaration) t);
            if (projectParent.isPresent()) {
                int before = bases.size();
                Optional<String> parentEntity = repositoryInfo(ctx, projectParent.get(), bases, visited);
                if (bases.size() > before && entity.isEmpty()) {
                    // Ara arayüz generic ise (BaseRepo<T>) entity bu arayüzün tip argümanıdır
                    entity = firstArg.filter(ctx.sources()::isProjectType).or(() -> parentEntity);
                }
            }
        }
        return entity;
    }

    private static Optional<String> stringAttribute(JavaContext ctx, AnnotationExpr annotation, String name) {
        return JavaNames.attribute(annotation, name).stream()
                .findFirst()
                .flatMap(ctx.constants()::evaluate)
                .filter(s -> !s.isBlank());
    }

    /**
     * Spring Boot'un varsayılan {@code CamelCaseToUnderscoresNamingStrategy} kuralı: büyük harf öncesine '_' eklenir,
     * küçük harfe çevrilir ({@code CreditInstallment} → {@code credit_installment}).
     */
    static String snakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.replace('.', '_'));
        for (int i = 1; i < sb.length() - 1; i++) {
            if (Character.isLowerCase(sb.charAt(i - 1)) && Character.isUpperCase(sb.charAt(i))
                    && Character.isLowerCase(sb.charAt(i + 1))) {
                sb.insert(i++, '_');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return Set.copyOf(result);
    }
}
