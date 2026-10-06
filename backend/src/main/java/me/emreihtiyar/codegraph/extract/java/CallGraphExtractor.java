package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithStatements;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import me.emreihtiyar.codegraph.extract.model.CallFact;
import me.emreihtiyar.codegraph.extract.model.CallTargetKind;
import me.emreihtiyar.codegraph.extract.model.Resolution;

/**
 * Servis içi tam metot çağrı grafı (PRD FR-04, FR-05).
 *
 * <p>Her metot gövdesindeki çağrılar <b>çalışma sırasıyla</b> toplanır: düğümler kaynak konumuna göre sıralanmış
 * post-order gezilir, böylece argüman ve scope'taki çağrılar dıştaki çağrıdan önce gelir. JDK/kütüphane çağrıları
 * kaydedilmez. Hedef şu sırayla bulunur: alıcı (receiver) tipi → {@link TypeModel} hiyerarşisinde ada ve argüman
 * sayısına göre metot → soyut/arayüz metodunda implementasyonlar. Lombok ile üretilen metotlar symbol solver'da
 * görünmediğinden metot araması her zaman modelde yapılır; symbol solver yalnızca alıcı tipini bulmada ve overload
 * seçiminde yardımcıdır.
 */
public final class CallGraphExtractor {

    private static final Set<String> ASYNC_CALLS = Set.of(
            "runAsync", "supplyAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync", "thenComposeAsync",
            "thenCombineAsync", "whenCompleteAsync", "handleAsync", "submit", "execute", "schedule",
            "scheduleAtFixedRate", "scheduleWithFixedDelay");
    private static final Set<String> ITERATING_CALLS = Set.of("forEach", "removeIf", "replaceAll", "forEachOrdered");
    private static final Set<String> STREAM_SOURCES =
            Set.of("stream", "parallelStream", "range", "rangeClosed", "iterate", "generate", "lines", "chars");
    private static final int MAX_DEPTH = 8;

    private record Target(CallTargetKind kind, TypeModel.MethodInfo method) {}

    private record Outcome(List<Target> targets, Resolution resolution, boolean library) {
        static final Outcome LIBRARY = new Outcome(List.of(), Resolution.RESOLVED, true);
        static final Outcome UNRESOLVED = new Outcome(List.of(), Resolution.UNRESOLVED, false);
    }

    private record Flags(
            boolean conditional, String conditionText, boolean inLoop, boolean async, boolean viaLambda) {}

    private JavaContext ctx;
    private TypeModel model;
    private Set<String> projectMethodNames;

    /** @param model bu repo + LIBRARY repolarının birleşik modeli */
    public void extract(JavaContext ctx, TypeModel model) {
        this.ctx = ctx;
        this.model = model;
        this.projectMethodNames = new HashSet<>();
        model.types().forEach(t -> t.methods().forEach(m -> projectMethodNames.add(m.name())));

        for (JavaSourceSet.JavaFile file : ctx.sources().files()) {
            for (TypeDeclaration<?> type : file.cu().findAll(TypeDeclaration.class)) {
                Optional<String> fqn = type.getFullyQualifiedName();
                Optional<TypeModel.TypeInfo> info = fqn.flatMap(model::type);
                if (info.isEmpty() || info.get().kind() == TypeModel.Kind.FEIGN_CLIENT
                        || info.get().kind() == TypeModel.Kind.REPOSITORY) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    method.getBody().ifPresent(body -> extractMethod(type, info.get(), method, body));
                }
            }
        }
    }

    private void extractMethod(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, MethodDeclaration method, BlockStmt body) {
        String callerFqn = JavaNames.methodFqn(owner.fqn(), method);
        List<Node> calls = new ArrayList<>();
        collectInExecutionOrder(body, calls);
        int order = 0;
        for (Node node : calls) {
            // Yerel/anonim sınıflardaki metotlar kendi başına düğüm değildir; çağrıları çevreleyen metoda yazılır
            Outcome outcome;
            try {
                outcome = node instanceof MethodCallExpr call
                        ? resolveCall(type, owner, call, 0)
                        : resolveReference(type, owner, (MethodReferenceExpr) node);
            } catch (RuntimeException | StackOverflowError e) {
                outcome = Outcome.UNRESOLVED;
            }
            if (outcome.library()) {
                continue;
            }
            order++;
            Flags flags = flags(node, body);
            int line = (node instanceof MethodCallExpr call ? call.getName().getBegin() : node.getBegin())
                    .map(p -> p.line)
                    .orElse(0);
            String text = abbreviate(node.toString());
            if (outcome.targets().isEmpty()) {
                ctx.facts().calls().add(new CallFact(callerFqn, CallTargetKind.METHOD, null, text, order, line,
                        flags.conditional(), flags.conditionText(), flags.inLoop(), flags.async(), flags.viaLambda(),
                        false, Resolution.UNRESOLVED));
                continue;
            }
            for (Target target : outcome.targets()) {
                String key = target.kind() == CallTargetKind.FEIGN_METHOD ? feignKey(target) : target.method().key();
                ctx.facts().calls().add(new CallFact(callerFqn, target.kind(), key, text, order, line,
                        flags.conditional(), flags.conditionText(), flags.inLoop(),
                        flags.async() || target.method().async(), flags.viaLambda(), key.equals(callerFqn),
                        outcome.resolution()));
            }
        }
    }

    // ---------------------------------------------------------------- gezinme

    /** Konuma göre sıralı post-order: çocuklar (soldan sağa) önce, düğüm sonra. */
    private static void collectInExecutionOrder(Node node, List<Node> out) {
        List<Node> children = new ArrayList<>(node.getChildNodes());
        children.sort(Comparator.comparing(
                (Node n) -> n.getBegin().map(p -> p.line * 100_000L + p.column).orElse(Long.MAX_VALUE)));
        for (Node child : children) {
            if (child instanceof TypeDeclaration<?>) {
                continue; // yerel sınıf bildirimleri
            }
            collectInExecutionOrder(child, out);
        }
        if (node instanceof MethodCallExpr || node instanceof MethodReferenceExpr ref && !ref.getIdentifier().equals("new")) {
            out.add(node);
        }
    }

    private Flags flags(Node call, BlockStmt body) {
        boolean conditional = false;
        String conditionText = null;
        boolean inLoop = false;
        boolean async = false;
        boolean viaLambda = call instanceof MethodReferenceExpr;
        if (viaLambda) {
            async = isAsyncArgument(call);
            inLoop = isIteratingArgument(call);
        }
        Node child = call;
        Optional<Node> parentOpt = call.getParentNode();
        while (parentOpt.isPresent() && child != body) {
            Node parent = parentOpt.get();
            final Node current = child;
            String cond = null;
            if (parent instanceof IfStmt ifs) {
                if (child == ifs.getThenStmt()) {
                    cond = "if (" + ifs.getCondition() + ")";
                } else if (ifs.getElseStmt().filter(e -> e == current).isPresent()) {
                    cond = "else: if (" + ifs.getCondition() + ")";
                }
            } else if (parent instanceof ConditionalExpr ce && child != ce.getCondition()) {
                cond = (child == ce.getThenExpr() ? "" : "else: ") + ce.getCondition() + " ? :";
            } else if (parent instanceof SwitchEntry entry) {
                String selector = parent.getParentNode()
                        .map(p -> p instanceof SwitchStmt s ? s.getSelector().toString()
                                : p instanceof com.github.javaparser.ast.expr.SwitchExpr s ? s.getSelector().toString()
                                : "?")
                        .orElse("?");
                cond = "switch (" + selector + ") " + (entry.getLabels().isEmpty() ? "default" : "case " + entry.getLabels());
            } else if (parent instanceof BinaryExpr be && child == be.getRight()
                    && (be.getOperator() == BinaryExpr.Operator.AND || be.getOperator() == BinaryExpr.Operator.OR)) {
                cond = be.getLeft() + (be.getOperator() == BinaryExpr.Operator.AND ? " && …" : " || …");
            } else if (parent instanceof CatchClause cc) {
                cond = "catch (" + cc.getParameter().getType() + ")";
            } else if (parent instanceof ForStmt fs && (child == fs.getBody() || fs.getCompare().filter(c -> c == current).isPresent()
                    || fs.getUpdate().contains(child))) {
                inLoop = true;
            } else if (parent instanceof ForEachStmt fe && child == fe.getBody()) {
                inLoop = true;
            } else if (parent instanceof WhileStmt || parent instanceof DoStmt) {
                inLoop = true;
            } else if (parent instanceof LambdaExpr) {
                viaLambda = true;
                async |= isAsyncArgument(parent);
                inLoop |= isIteratingArgument(parent);
            } else if (parent instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()
                    && child instanceof com.github.javaparser.ast.body.BodyDeclaration<?>) {
                viaLambda = true;
                async |= isAsyncArgument(parent) || oce.getType().getNameAsString().equals("Thread");
            }
            if (cond != null) {
                if (!conditional) {
                    conditionText = cond;
                }
                conditional = true;
            }
            child = parent;
            parentOpt = parent.getParentNode();
        }
        return new Flags(conditional, conditionText, inLoop, async, viaLambda);
    }

    /** Lambda/method reference'ın argüman olduğu çağrı asenkron bir çalıştırıcı mı? */
    private static boolean isAsyncArgument(Node functional) {
        return functional.getParentNode()
                .filter(p -> p instanceof MethodCallExpr)
                .map(p -> ASYNC_CALLS.contains(((MethodCallExpr) p).getNameAsString()))
                .orElse(false)
                || functional.getParentNode()
                        .filter(p -> p instanceof ObjectCreationExpr o && o.getType().getNameAsString().equals("Thread"))
                        .isPresent();
    }

    /** {@code list.forEach(...)} ya da bir stream zincirindeki ara/son işlem. */
    private static boolean isIteratingArgument(Node functional) {
        Optional<Node> parent = functional.getParentNode();
        if (parent.isEmpty() || !(parent.get() instanceof MethodCallExpr call)) {
            return false;
        }
        if (ITERATING_CALLS.contains(call.getNameAsString())) {
            return true;
        }
        Optional<Expression> scope = call.getScope();
        while (scope.isPresent() && scope.get() instanceof MethodCallExpr inner) {
            if (STREAM_SOURCES.contains(inner.getNameAsString())) {
                return true;
            }
            scope = inner.getScope();
        }
        return false;
    }

    // ---------------------------------------------------------------- hedef çözümleme

    private Outcome resolveCall(TypeDeclaration<?> type, TypeModel.TypeInfo owner, MethodCallExpr call, int depth) {
        String name = call.getNameAsString();
        int args = call.getArguments().size();
        if (call.getScope().isEmpty()) {
            return resolveUnscoped(type, owner, call, name, args);
        }
        Expression scope = call.getScope().get();
        Optional<String> receiver = expressionType(type, owner, scope, depth + 1);
        if (receiver.isEmpty()) {
            return projectMethodNames.contains(name) ? Outcome.UNRESOLVED : Outcome.LIBRARY;
        }
        if (scope instanceof SuperExpr) {
            return resolveIn(receiver.get(), name, m -> m.accepts(args), call, false, scope);
        }
        return resolveIn(receiver.get(), name, m -> m.accepts(args), call, true, scope);
    }

    /** Niteliksiz çağrı: çevreleyen tip(ler)in hiyerarşisi → statik importlar. */
    private Outcome resolveUnscoped(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, MethodCallExpr call, String name, int args) {
        Optional<Node> current = Optional.of(call);
        while (current.isPresent()) {
            if (current.get() instanceof TypeDeclaration<?> enclosing) {
                Optional<String> fqn = enclosing.getFullyQualifiedName();
                if (fqn.isPresent() && !model.findMethods(fqn.get(), name).stream()
                        .filter(m -> m.accepts(args)).toList().isEmpty()) {
                    // Niteliksiz çağrı this üzerinden yapılır: alt sınıflardaki override'lar da adaydır
                    return resolveIn(fqn.get(), name, m -> m.accepts(args), call, true, null);
                }
            }
            current = current.get().getParentNode();
        }
        Optional<CompilationUnit> cu = call.findCompilationUnit();
        if (cu.isPresent()) {
            for (ImportDeclaration imp : cu.get().getImports()) {
                if (!imp.isStatic()) {
                    continue;
                }
                String imported = imp.getNameAsString();
                String typeFqn = imp.isAsterisk() ? imported
                        : imported.endsWith("." + name) ? imported.substring(0, imported.lastIndexOf('.')) : null;
                if (typeFqn != null && model.contains(typeFqn)) {
                    return resolveIn(typeFqn, name, m -> m.accepts(args), call, false, null);
                }
            }
        }
        return model.hasExternalAncestor(owner.fqn()) || !projectMethodNames.contains(name)
                ? Outcome.LIBRARY
                : Outcome.UNRESOLVED;
    }

    private Outcome resolveReference(TypeDeclaration<?> type, TypeModel.TypeInfo owner, MethodReferenceExpr ref) {
        Expression scope = ref.getScope();
        Optional<String> receiver = scope instanceof TypeExpr te
                ? te.getType().findCompilationUnit()
                        .flatMap(cu -> ctx.sources().resolveTypeName(cu, te.getType().asString().replaceAll("<.*>", "")))
                : expressionType(type, owner, scope, 1);
        if (receiver.isEmpty()) {
            return projectMethodNames.contains(ref.getIdentifier()) ? Outcome.UNRESOLVED : Outcome.LIBRARY;
        }
        return resolveIn(receiver.get(), ref.getIdentifier(), m -> true, null, !(scope instanceof TypeExpr), scope);
    }

    /**
     * @param dynamicDispatch alıcı çalışma zamanında alt tip olabilir mi (super. çağrılarında ve statik çağrılarda
     *                        hayır); evetse soyut/arayüz metotları implementasyonlara açılır
     */
    private Outcome resolveIn(
            String receiver,
            String name,
            java.util.function.Predicate<TypeModel.MethodInfo> arity,
            MethodCallExpr call,
            boolean dynamicDispatch,
            Expression scope) {
        Optional<TypeModel.TypeInfo> receiverType = model.type(receiver);
        if (receiverType.isEmpty()) {
            return Outcome.LIBRARY;
        }
        List<TypeModel.MethodInfo> candidates =
                model.findMethods(receiver, name).stream().filter(arity).toList();
        if (candidates.isEmpty()) {
            return model.hasExternalAncestor(receiver) ? Outcome.LIBRARY : Outcome.UNRESOLVED;
        }
        if (candidates.size() > 1 && call != null) {
            candidates = pickOverload(call, candidates);
        }

        TypeModel.Kind kind = receiverType.get().kind();
        if (kind == TypeModel.Kind.FEIGN_CLIENT) {
            // Üst arayüzden miras alınan metot da alıcı Feign istemcisinin metodu sayılır
            return new Outcome(candidates.stream()
                    .map(m -> new Target(CallTargetKind.FEIGN_METHOD, withDeclaringType(m, receiver)))
                    .toList(), resolution(candidates.size()), false);
        }
        if (kind == TypeModel.Kind.REPOSITORY) {
            return new Outcome(candidates.stream()
                    .map(m -> new Target(CallTargetKind.REPOSITORY_METHOD, m))
                    .toList(), resolution(candidates.size()), false);
        }

        Set<TypeModel.MethodInfo> targets = new LinkedHashSet<>();
        boolean polymorphic = false;
        for (TypeModel.MethodInfo m : candidates) {
            if (dynamicDispatch && m.isAbstract()) {
                List<TypeModel.MethodInfo> impls = implementations(m, scope);
                if (impls.isEmpty()) {
                    targets.add(m);
                } else {
                    targets.addAll(impls);
                    polymorphic |= impls.size() > 1;
                }
            } else {
                targets.add(m);
            }
        }
        Resolution resolution = polymorphic || targets.size() > 1 ? Resolution.AMBIGUOUS : Resolution.RESOLVED;
        return new Outcome(targets.stream().map(m -> new Target(CallTargetKind.METHOD, m)).toList(), resolution, false);
    }

    private static Resolution resolution(int count) {
        return count > 1 ? Resolution.AMBIGUOUS : Resolution.RESOLVED;
    }

    /**
     * Soyut/arayüz metodunun somut implementasyonları. Birden çok aday varsa {@code @Primary} ve alan/constructor
     * parametresindeki {@code @Qualifier} ipuçlarıyla daraltılır (PRD §11.1).
     */
    private List<TypeModel.MethodInfo> implementations(TypeModel.MethodInfo method, Expression scope) {
        List<TypeModel.TypeInfo> concrete = model.allSubTypes(method.declaringType()).stream()
                .filter(t -> t.kind() == TypeModel.Kind.CLASS || t.kind() == TypeModel.Kind.RECORD
                        || t.kind() == TypeModel.Kind.ENUM)
                .toList();
        List<TypeModel.TypeInfo> withImpl = new ArrayList<>();
        Set<TypeModel.MethodInfo> impls = new LinkedHashSet<>();
        for (TypeModel.TypeInfo type : concrete) {
            implementationIn(type, method).ifPresent(m -> {
                impls.add(m);
                withImpl.add(type);
            });
        }
        if (impls.size() > 1) {
            Optional<String> qualifier = qualifier(scope);
            List<TypeModel.TypeInfo> preferred = withImpl.stream()
                    .filter(t -> qualifier.map(q -> q.equals(t.beanName())).orElse(t.primary()))
                    .toList();
            if (preferred.size() == 1) {
                return implementationIn(preferred.get(0), method).map(List::of).orElse(List.copyOf(impls));
            }
        }
        return List.copyOf(impls);
    }

    /** Somut tipin hiyerarşisinde metodu karşılayan ilk somut metot. */
    private Optional<TypeModel.MethodInfo> implementationIn(TypeModel.TypeInfo type, TypeModel.MethodInfo method) {
        for (TypeModel.TypeInfo t : model.hierarchy(type.fqn())) {
            for (TypeModel.MethodInfo m : t.methods()) {
                if (!m.isAbstract() && !m.isStatic() && m.name().equals(method.name())
                        && (m.signatureKey().equals(method.signatureKey()) || m.paramCount() == method.paramCount())) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    /** Alıcı bir alan ise alanın ya da aynı adlı constructor parametresinin {@code @Qualifier} değeri. */
    private static Optional<String> qualifier(Expression scope) {
        String fieldName = scope instanceof NameExpr n ? n.getNameAsString()
                : scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr ? fa.getNameAsString()
                : null;
        if (fieldName == null) {
            return Optional.empty();
        }
        Optional<TypeDeclaration> type = scope.findAncestor(TypeDeclaration.class);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        TypeDeclaration<?> td = type.get();
        for (FieldDeclaration field : td.getFields()) {
            if (field.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(fieldName))) {
                Optional<String> q = qualifierValue(field);
                if (q.isPresent()) {
                    return q;
                }
            }
        }
        for (ConstructorDeclaration ctor : td.getConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                if (p.getNameAsString().equals(fieldName)) {
                    Optional<String> q = qualifierValue(p);
                    if (q.isPresent()) {
                        return q;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> qualifierValue(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return JavaNames.annotation(node, "Qualifier")
                .flatMap(a -> JavaNames.attribute(a, "value").stream().findFirst())
                .map(e -> e.toString().replaceAll("^\"|\"$", ""));
    }

    /** Aynı ada ve argüman sayısına sahip overload'lar arasında symbol solver ile seçim. */
    private List<TypeModel.MethodInfo> pickOverload(MethodCallExpr call, List<TypeModel.MethodInfo> candidates) {
        try {
            var resolved = call.resolve();
            Optional<String> key = resolved.toAst()
                    .filter(n -> n instanceof CallableDeclaration<?>)
                    .flatMap(n -> JavaNames.methodFqn((CallableDeclaration<?>) n));
            if (key.isPresent()) {
                String signature = key.get().substring(key.get().indexOf('#') + 1);
                List<TypeModel.MethodInfo> match = candidates.stream()
                        .filter(m -> m.signatureKey().equals(signature))
                        .toList();
                if (!match.isEmpty()) {
                    return match;
                }
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // Çözülemezse tüm adaylar AMBIGUOUS olarak kalır
        }
        return candidates;
    }

    private static TypeModel.MethodInfo withDeclaringType(TypeModel.MethodInfo m, String declaringType) {
        return new TypeModel.MethodInfo(declaringType + "#" + m.signatureKey(), declaringType, m.name(),
                m.parameterTypes(), m.paramCount(), m.varArgs(), m.returnType(), m.isAbstract(), m.isStatic(), m.async());
    }

    private static String feignKey(Target target) {
        return target.method().key();
    }

    // ---------------------------------------------------------------- ifade tipleri

    /** İfadenin tipinin FQN'i (proje dışı tipler dahil). Bulunamazsa boş. */
    private Optional<String> expressionType(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, Expression expr, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (expr instanceof ThisExpr te) {
            return te.getTypeName().isPresent()
                    ? ctx.sources().resolveTypeName(type.findCompilationUnit().orElseThrow(), te.getTypeName().get().asString())
                    : enclosingTypeFqn(expr).or(() -> Optional.of(owner.fqn()));
        }
        if (expr instanceof SuperExpr) {
            String self = enclosingTypeFqn(expr).orElse(owner.fqn());
            return model.type(self).flatMap(t -> t.superTypes().stream()
                    .filter(s -> model.type(s).map(i -> i.kind() != TypeModel.Kind.INTERFACE).orElse(true))
                    .findFirst());
        }
        if (expr instanceof EnclosedExpr e) {
            return expressionType(type, owner, e.getInner(), depth + 1);
        }
        if (expr instanceof CastExpr c) {
            return typeFqn(c, c.getType());
        }
        if (expr instanceof ObjectCreationExpr oce) {
            return typeFqn(oce, oce.getType());
        }
        if (expr instanceof NameExpr name) {
            Optional<Optional<String>> variable = variableType(type, owner, name, name.getNameAsString(), depth);
            if (variable.isPresent()) {
                return variable.get();
            }
            // Değişken değilse statik çağrı için tip adı
            Optional<String> asType = name.findCompilationUnit()
                    .flatMap(cu -> ctx.sources().resolveTypeName(cu, name.getNameAsString()));
            if (asType.isPresent()) {
                return asType;
            }
            return solverType(expr);
        }
        if (expr instanceof FieldAccessExpr fa) {
            if (fa.getScope() instanceof ThisExpr) {
                return model.fieldType(enclosingTypeFqn(expr).orElse(owner.fqn()), fa.getNameAsString())
                        .or(() -> solverType(expr));
            }
            // Nitelikli tip adı (paket.Sinif veya Dis.Ic)
            Optional<String> asType = Character.isUpperCase(fa.getNameAsString().charAt(0))
                    ? fa.findCompilationUnit().flatMap(cu -> ctx.sources().resolveTypeName(cu, fa.toString()))
                            .filter(model::contains)
                    : Optional.empty();
            if (asType.isPresent()) {
                return asType;
            }
            Optional<String> scopeType = expressionType(type, owner, fa.getScope(), depth + 1);
            return scopeType.flatMap(t -> model.fieldType(t, fa.getNameAsString())).or(() -> solverType(expr));
        }
        if (expr instanceof MethodCallExpr inner) {
            Outcome outcome;
            try {
                outcome = resolveCall(type, owner, inner, depth);
            } catch (RuntimeException | StackOverflowError e) {
                outcome = Outcome.UNRESOLVED;
            }
            if (!outcome.library() && !outcome.targets().isEmpty()) {
                String returnType = outcome.targets().get(0).method().returnType();
                if (returnType != null && model.contains(returnType)) {
                    return Optional.of(returnType);
                }
                if (returnType != null && returnType.contains(".")) {
                    // Proje dışı dönüş tipi (Optional, List ...): generic eleman tipini solver bulabilir
                    return solverType(expr).or(() -> Optional.of(returnType));
                }
            }
            return solverType(expr);
        }
        return solverType(expr);
    }

    /**
     * Ada göre değişken tipi: lambda parametreleri, önceki yerel değişkenler, for/catch/try değişkenleri, metot
     * parametreleri, alanlar (çevreleyen tipler ve üst tipleri). Dış Optional boşsa ad bir değişken değildir; iç
     * Optional boşsa değişkendir ama tipi bilinmiyor.
     */
    private Optional<Optional<String>> variableType(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, Node from, String name, int depth) {
        Node child = from;
        Optional<Node> parentOpt = from.getParentNode();
        while (parentOpt.isPresent()) {
            Node parent = parentOpt.get();
            if (parent instanceof LambdaExpr lambda) {
                for (Parameter p : lambda.getParameters()) {
                    if (p.getNameAsString().equals(name)) {
                        return Optional.of(p.getType().isUnknownType()
                                ? solverType(from instanceof Expression e ? e : null)
                                : typeFqn(p, p.getType()));
                    }
                }
            } else if (parent instanceof NodeWithStatements<?> block) {
                Optional<Optional<String>> local = localVariable(type, owner, block.getStatements(), child, name, depth);
                if (local.isPresent()) {
                    return local;
                }
            } else if (parent instanceof ForEachStmt fe) {
                for (VariableDeclarator v : fe.getVariable().getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return Optional.of(declaredType(type, owner, v, depth));
                    }
                }
            } else if (parent instanceof ForStmt fs) {
                for (Expression init : fs.getInitialization()) {
                    if (init instanceof VariableDeclarationExpr vde) {
                        for (VariableDeclarator v : vde.getVariables()) {
                            if (v.getNameAsString().equals(name)) {
                                return Optional.of(declaredType(type, owner, v, depth));
                            }
                        }
                    }
                }
            } else if (parent instanceof TryStmt ts) {
                for (Expression resource : ts.getResources()) {
                    if (resource instanceof VariableDeclarationExpr vde) {
                        for (VariableDeclarator v : vde.getVariables()) {
                            if (v.getNameAsString().equals(name)) {
                                return Optional.of(declaredType(type, owner, v, depth));
                            }
                        }
                    }
                }
            } else if (parent instanceof CatchClause cc && cc.getParameter().getNameAsString().equals(name)) {
                return Optional.of(typeFqn(cc, cc.getParameter().getType()));
            } else if (parent instanceof CallableDeclaration<?> callable) {
                for (Parameter p : callable.getParameters()) {
                    if (p.getNameAsString().equals(name)) {
                        return Optional.of(typeFqn(p, p.getType()));
                    }
                }
            } else if (parent instanceof TypeDeclaration<?> td) {
                Optional<String> fqn = td.getFullyQualifiedName();
                if (fqn.isPresent()) {
                    Optional<String> field = model.fieldType(fqn.get(), name);
                    if (field.isPresent()) {
                        return Optional.of(field);
                    }
                }
            }
            child = parent;
            parentOpt = parent.getParentNode();
        }
        return Optional.empty();
    }

    private Optional<Optional<String>> localVariable(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, List<Statement> statements, Node child, String name,
            int depth) {
        for (Statement stmt : statements) {
            if (stmt == child) {
                break;
            }
            if (stmt instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return Optional.of(declaredType(type, owner, v, depth));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** {@code var} ise başlatıcı ifadenin tipi, değilse bildirilen tip. */
    private Optional<String> declaredType(
            TypeDeclaration<?> type, TypeModel.TypeInfo owner, VariableDeclarator v, int depth) {
        if (v.getType().isVarType()) {
            return v.getInitializer().flatMap(init -> expressionType(type, owner, init, depth + 1));
        }
        return typeFqn(v, v.getType());
    }

    private Optional<String> typeFqn(Node context, Type type) {
        if (type.isPrimitiveType() || type.isVoidType() || type.isArrayType()) {
            return Optional.empty();
        }
        String name = type.isClassOrInterfaceType() ? type.asClassOrInterfaceType().getNameWithScope()
                : JavaNames.simpleTypeName(type);
        return context.findCompilationUnit().flatMap(cu -> ctx.sources().resolveTypeName(cu, name));
    }

    private static Optional<String> enclosingTypeFqn(Node node) {
        return node.findAncestor(TypeDeclaration.class).flatMap(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName());
    }

    private static Optional<String> solverType(Expression expr) {
        if (expr == null) {
            return Optional.empty();
        }
        try {
            ResolvedType resolved = expr.calculateResolvedType();
            if (resolved.isReferenceType()) {
                return Optional.of(resolved.asReferenceType().getQualifiedName());
            }
        } catch (RuntimeException | StackOverflowError ignored) {
            // Kütüphane tipleri sınıf yolunda değil; çözülemeyen ifade tipi bilinmiyor sayılır
        }
        return Optional.empty();
    }

    private static String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() > 160 ? oneLine.substring(0, 157) + "..." : oneLine;
    }
}
