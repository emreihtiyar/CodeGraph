package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Anotasyon değerlerindeki derleme zamanı sabit metin ifadelerini değerlendirir: literal'ler, {@code +} birleştirme
 * ve proje içindeki {@code static final String} alanları (aynı sınıf, {@code Sinif.ALAN}, statik import). Ardından
 * {@code ${...}} placeholder'ları application dosyalarından çözülür.
 */
public final class ConstantResolver {

    private final JavaSourceSet sources;
    private final SpringProperties properties;

    public ConstantResolver(JavaSourceSet sources, SpringProperties properties) {
        this.sources = sources;
        this.properties = properties;
    }

    /** Değerlendirilip placeholder'ları çözülmüş metin; sabit değilse boş. */
    public Optional<String> evaluate(Expression expression) {
        return evaluate(expression, new HashSet<>()).map(properties::resolve);
    }

    private Optional<String> evaluate(Expression expr, Set<String> visiting) {
        if (expr instanceof StringLiteralExpr literal) {
            return Optional.of(literal.asString());
        }
        if (expr instanceof TextBlockLiteralExpr block) {
            return Optional.of(block.asString());
        }
        if (expr instanceof EnclosedExpr enclosed) {
            return evaluate(enclosed.getInner(), visiting);
        }
        if (expr instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = evaluate(binary.getLeft(), visiting);
            Optional<String> right = evaluate(binary.getRight(), visiting);
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        if (expr instanceof NameExpr name) {
            return resolveName(expr, name.getNameAsString(), visiting);
        }
        if (expr instanceof FieldAccessExpr access) {
            String scope = access.getScope().toString();
            return expr.findCompilationUnit()
                    .flatMap(cu -> sources.resolveTypeName(cu, scope))
                    .flatMap(sources::type)
                    .flatMap(type -> field(type, access.getNameAsString(), visiting));
        }
        return Optional.empty();
    }

    /** Niteliksiz ad: önce çevreleyen tipler (iç içe), sonra statik importlar. */
    private Optional<String> resolveName(Expression expr, String name, Set<String> visiting) {
        Optional<com.github.javaparser.ast.Node> current = expr.getParentNode();
        while (current.isPresent()) {
            if (current.get() instanceof TypeDeclaration<?> type) {
                Optional<String> value = field(type, name, visiting);
                if (value.isPresent()) {
                    return value;
                }
            }
            current = current.get().getParentNode();
        }
        Optional<CompilationUnit> cu = expr.findCompilationUnit();
        if (cu.isEmpty()) {
            return Optional.empty();
        }
        for (ImportDeclaration imp : cu.get().getImports()) {
            if (!imp.isStatic()) {
                continue;
            }
            String imported = imp.getNameAsString();
            String typeName = imp.isAsterisk() ? imported : imported.substring(0, Math.max(0, imported.lastIndexOf('.')));
            if (imp.isAsterisk() || imported.endsWith("." + name)) {
                Optional<String> value = sources.type(typeName).flatMap(t -> field(t, name, visiting));
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> field(TypeDeclaration<?> type, String name, Set<String> visiting) {
        String key = JavaNames.classFqn(type) + "." + name;
        if (!visiting.add(key)) {
            return Optional.empty();
        }
        for (FieldDeclaration field : type.getFields()) {
            boolean constant = field.isFinal() || type.isClassOrInterfaceDeclaration()
                    && type.asClassOrInterfaceDeclaration().isInterface();
            if (!constant) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getNameAsString().equals(name) && variable.getInitializer().isPresent()) {
                    return evaluate(variable.getInitializer().get(), visiting);
                }
            }
        }
        return Optional.empty();
    }
}
