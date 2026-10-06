package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.ast.Node;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;
import me.emreihtiyar.codegraph.extract.model.RepoFacts;
import me.emreihtiyar.codegraph.extract.model.SourceRef;

/** Bir Java reposunun analizi boyunca extractor'ların paylaştığı durum. */
public record JavaContext(
        JavaSourceSet sources, SpringProperties properties, ConstantResolver constants, RepoFacts facts) {

    /** Kod bloğu bu boyutu aşarsa yalnızca dosya/satır referansı saklanır (PRD §11.1). */
    public static final int MAX_CODE_CHARS = 64 * 1024;

    public SourceRef sourceRef(Node node, JavaSourceSet.JavaFile file) {
        int begin = node.getBegin().map(p -> p.line).orElse(0);
        int end = node.getEnd().map(p -> p.line).orElse(begin);
        return new SourceRef(file.path(), begin, end);
    }

    /** Düğümün kaynak metni; anotasyon ve Javadoc dahil, girinti korunur. */
    public String code(Node node, JavaSourceSet.JavaFile file) {
        int begin = node.getComment().flatMap(Node::getBegin).or(node::getBegin).map(p -> p.line).orElse(0);
        int end = node.getEnd().map(p -> p.line).orElse(begin);
        if (begin <= 0) {
            return null;
        }
        String code = file.source().lines().skip(begin - 1L).limit((long) end - begin + 1).reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return code.length() > MAX_CODE_CHARS ? null : code;
    }

    public void issue(JavaSourceSet.JavaFile file, Node node, String message) {
        facts.issues().add(new ParseIssue(file.path(), node.getBegin().map(p -> p.line).orElse(0), message));
    }
}
