package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** AST'den isim, FQN ve anotasyon okuma yardımcıları. Anotasyonlar basit adlarıyla eşlenir (tam ad da kabul edilir). */
public final class JavaNames {

    private JavaNames() {}

    /** İç içe tiplerde {@code paket.Dis.Ic}. */
    public static String classFqn(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseGet(type::getNameAsString);
    }

    /** Metodu tanımlayan tipin FQN'i; anonim sınıf/enum sabiti içindeki metotlarda en yakın isimli tip. */
    public static Optional<String> declaringClassFqn(Node node) {
        return node.findAncestor(TypeDeclaration.class).map(t -> classFqn((TypeDeclaration<?>) t));
    }

    /**
     * {@code paket.Sinif#ad(ParamTip1,ParamTip2)}. Parametre tipleri kaynakta yazıldığı gibi, basit adla ve generic'siz
     * yazılır; böylece FQN tip çözümüne bağlı değildir ve aynı bildirim için her zaman aynı sonucu verir.
     */
    public static String methodFqn(String classFqn, CallableDeclaration<?> method) {
        return classFqn + "#" + method.getNameAsString() + "(" + parameterTypes(method) + ")";
    }

    public static Optional<String> methodFqn(CallableDeclaration<?> method) {
        return declaringClassFqn(method).map(c -> methodFqn(c, method));
    }

    public static String parameterTypes(CallableDeclaration<?> method) {
        return method.getParameters().stream().map(JavaNames::parameterType).collect(Collectors.joining(","));
    }

    private static String parameterType(Parameter parameter) {
        return simpleTypeName(parameter.getType()) + (parameter.isVarArgs() ? "..." : "");
    }

    /** {@code java.util.List<Foo>} → {@code List}, {@code Foo[]} → {@code Foo[]}. */
    public static String simpleTypeName(Type type) {
        if (type instanceof ArrayType array) {
            return simpleTypeName(array.getComponentType()) + "[]";
        }
        if (type instanceof ClassOrInterfaceType cit) {
            return cit.getNameAsString();
        }
        return type.asString();
    }

    /** {@code org.x.GetMapping} → {@code GetMapping}. */
    public static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String... simpleNames) {
        List<String> names = Arrays.asList(simpleNames);
        return node.getAnnotations().stream()
                .filter(a -> names.contains(simpleName(a.getNameAsString())))
                .findFirst();
    }

    public static boolean hasAnnotation(NodeWithAnnotations<?> node, String... simpleNames) {
        return annotation(node, simpleNames).isPresent();
    }

    /**
     * Anotasyon niteliğinin değer(ler)i; dizi başlatıcıları düzleştirilir. {@code value} niteliği tek-üyeli
     * anotasyondan da okunur. Birden çok ad verilirse (ör. {@code "value", "path"}) ilk bulunan döner.
     */
    public static List<Expression> attribute(AnnotationExpr annotation, String... names) {
        for (String name : names) {
            Expression value = null;
            if (annotation instanceof SingleMemberAnnotationExpr single && name.equals("value")) {
                value = single.getMemberValue();
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                value = normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals(name))
                        .map(MemberValuePair::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (value != null) {
                List<Expression> values = new ArrayList<>();
                if (value instanceof ArrayInitializerExpr array) {
                    values.addAll(array.getValues());
                } else {
                    values.add(value);
                }
                return values;
            }
        }
        return List.of();
    }
}
