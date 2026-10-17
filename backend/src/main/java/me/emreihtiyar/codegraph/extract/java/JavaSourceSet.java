package me.emreihtiyar.codegraph.extract.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.emreihtiyar.codegraph.extract.SourceText;
import me.emreihtiyar.codegraph.extract.model.ParseIssue;

/**
 * Bir reponun ayrıştırılmış Java kaynakları, symbol solver yapılandırması ve proje tip indeksi.
 *
 * <p>Tip çözümleyici yalnızca JRE sınıflarını ve proje kaynaklarını (bu repo + {@code LIBRARY} repoları) tanır;
 * Spring, Feign, JPA gibi kütüphaneler sınıf yolunda değildir. Bu yüzden anotasyonlar isimle okunur ve kütüphane
 * metotlarının içine inilmez (PRD FR-04).
 */
public final class JavaSourceSet {

    static {
        // JavaParser sınıfları yüklenmeden önce olmalı (bkz. ParserLocale)
        ParserLocale.ensureSafeDefault();
    }

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /** @param path repo köküne göreli, '/' ayraçlı */
    public record JavaFile(String path, CompilationUnit cu, String source) {}

    private final List<JavaFile> files;
    private final List<ParseIssue> issues;
    private final Map<String, TypeDeclaration<?>> typesByFqn;
    private final Map<String, List<String>> fqnsBySimpleName;

    private JavaSourceSet(
            List<JavaFile> files,
            List<ParseIssue> issues,
            Map<String, TypeDeclaration<?>> typesByFqn,
            Map<String, List<String>> fqnsBySimpleName) {
        this.files = files;
        this.issues = issues;
        this.typesByFqn = typesByFqn;
        this.fqnsBySimpleName = fqnsBySimpleName;
    }

    /**
     * @param repoRoot     repo kökü
     * @param sources      repo köküne göreli Java dosyaları
     * @param libraryRoots tip çözümü için ek kaynak kökleri (LIBRARY repolarının Java dosyaları, mutlak)
     */
    public static JavaSourceSet parse(Path repoRoot, List<Path> sources, Collection<Path> libraryRoots) {
        List<ParseIssue> issues = new ArrayList<>();
        Map<Path, String> texts = new LinkedHashMap<>();
        Set<Path> sourceRoots = new LinkedHashSet<>();
        for (Path relative : sources) {
            Path file = repoRoot.resolve(relative);
            try {
                String text = SourceText.read(file);
                texts.put(relative, text);
                sourceRoot(file, text).ifPresent(sourceRoots::add);
            } catch (IOException | RuntimeException e) {
                issues.add(new ParseIssue(unix(relative), 0, "Dosya okunamadı: " + e.getMessage()));
            }
        }
        sourceRoots.addAll(libraryRoots);

        JavaParser parser = createParser(sourceRoots);
        List<JavaFile> files = new ArrayList<>();
        Map<String, TypeDeclaration<?>> typesByFqn = new HashMap<>();
        Map<String, List<String>> bySimpleName = new HashMap<>();
        texts.forEach((relative, text) -> {
            ParseResult<CompilationUnit> result = parser.parse(text);
            if (result.getResult().isEmpty() || !result.isSuccessful()) {
                String problems = result.getProblems().stream()
                        .limit(3)
                        .map(p -> p.getVerboseMessage().lines().findFirst().orElse(""))
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("bilinmeyen hata");
                int line = result.getProblems().stream()
                        .flatMap(p -> p.getLocation().flatMap(l -> l.getBegin().getRange()).stream())
                        .map(r -> r.begin.line)
                        .findFirst()
                        .orElse(0);
                issues.add(new ParseIssue(unix(relative), line, "Java ayrıştırılamadı: " + problems));
                return;
            }
            CompilationUnit cu = result.getResult().get();
            files.add(new JavaFile(unix(relative), cu, text));
            cu.findAll(TypeDeclaration.class).forEach(t -> {
                TypeDeclaration<?> type = (TypeDeclaration<?>) t;
                type.getFullyQualifiedName().ifPresent(fqn -> {
                    typesByFqn.putIfAbsent(fqn, type);
                    bySimpleName.computeIfAbsent(type.getNameAsString(), k -> new ArrayList<>()).add(fqn);
                });
            });
        });
        return new JavaSourceSet(files, issues, typesByFqn, bySimpleName);
    }

    private static JavaParser createParser(Set<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(symbolSolver)
                .setAttributeComments(false);
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root, configuration));
        }
        return new JavaParser(configuration);
    }

    /** Dosya yolundan paket segmentleri çıkarılarak kaynak kökü bulunur. */
    private static Optional<Path> sourceRoot(Path file, String text) {
        Matcher m = PACKAGE.matcher(text);
        Path dir = file.getParent();
        if (m.find()) {
            String[] segments = m.group(1).split("\\.");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (dir == null || !dir.getFileName().toString().equals(segments[i])) {
                    return Optional.empty();
                }
                dir = dir.getParent();
            }
        }
        return Optional.ofNullable(dir);
    }

    /** Bu kaynak setindeki Java dosyalarının bulunduğu kaynak kökleri (LIBRARY repoları için dışa verilir). */
    public static Set<Path> sourceRoots(Path repoRoot, List<Path> sources) {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path relative : sources) {
            Path file = repoRoot.resolve(relative);
            try {
                sourceRoot(file, SourceText.read(file)).ifPresent(roots::add);
            } catch (IOException | RuntimeException e) {
                // Okunamayan dosya parse aşamasında raporlanır
            }
        }
        return roots;
    }

    public List<JavaFile> files() {
        return files;
    }

    public List<ParseIssue> issues() {
        return issues;
    }

    public Optional<TypeDeclaration<?>> type(String fqn) {
        return Optional.ofNullable(typesByFqn.get(fqn));
    }

    /**
     * Bir derleme birimi bağlamında basit tip adını FQN'e çevirir: nitelikli ad → tekil import (proje dışı tipler ve
     * LIBRARY repoları dahil) → aynı paketteki proje tipi → joker import → projede tek eşleşme. Bulunamazsa boş.
     */
    public Optional<String> resolveTypeName(CompilationUnit cu, String name) {
        if (name.contains(".")) {
            return Optional.of(name);
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic() && imp.getNameAsString().endsWith("." + name)) {
                return Optional.of(imp.getNameAsString());
            }
        }
        List<String> candidates = fqnsBySimpleName.getOrDefault(name, List.of());
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        if (candidates.contains(pkg + name)) {
            return Optional.of(pkg + name);
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() && !imp.isStatic() && candidates.contains(imp.getNameAsString() + "." + name)) {
                return Optional.of(imp.getNameAsString() + "." + name);
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /** Proje (bu repo) tipi mi? */
    public boolean isProjectType(String fqn) {
        return typesByFqn.containsKey(fqn);
    }

    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }
}
