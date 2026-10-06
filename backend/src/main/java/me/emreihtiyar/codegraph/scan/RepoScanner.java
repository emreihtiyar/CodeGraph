package me.emreihtiyar.codegraph.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import me.emreihtiyar.codegraph.config.RepoDefinition;
import org.springframework.stereotype.Component;

/**
 * Bir repoyu dolaşıp analiz edilecek dosyaları sınıflandırır (PRD §8.1 Scan). Dosyalara yalnızca okuma amaçlı
 * erişilir (NFR-04).
 *
 * <p>Java katmanlarında yalnızca Java kaynakları ve Spring application dosyaları, {@code FRONTEND} katmanında
 * yalnızca TS/JS kaynakları toplanır. Test kaynakları, bağımlılık ve build çıktısı dizinleri atlanır.
 */
@Component
public class RepoScanner {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".gradle", ".mvn", "node_modules", "coverage", ".next", "__tests__",
            "__mocks__");
    /** Build çıktıları; {@code src/} altında aynı adlı paketler (ör. {@code com/x/build}) atlanmaz. */
    private static final Set<String> OUTPUT_DIRS = Set.of("target", "build", "dist", "out");

    private static final Pattern APP_CONFIG =
            Pattern.compile("(application|bootstrap)(-[\\w.-]+)?\\.(ya?ml|properties)");
    private static final Pattern FRONTEND_SOURCE = Pattern.compile(".+\\.(ts|tsx|js|jsx)");
    private static final Pattern FRONTEND_EXCLUDED = Pattern.compile(".+\\.(d\\.ts|test\\.\\w+|spec\\.\\w+|stories\\.\\w+)");

    public ScannedRepo scan(RepoDefinition repo) {
        List<Path> javaSources = new ArrayList<>();
        List<Path> appConfigs = new ArrayList<>();
        List<Path> apiCallFiles = new ArrayList<>();
        List<Path> frontendSources = new ArrayList<>();
        boolean java = repo.layer().isJava();
        Path root = repo.path();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return isSkipped(root.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = root.relativize(file);
                    String name = file.getFileName().toString();
                    if (java) {
                        if (name.endsWith(".java")) {
                            javaSources.add(relative);
                        } else if (APP_CONFIG.matcher(name).matches()) {
                            appConfigs.add(relative);
                        }
                    } else if (FRONTEND_SOURCE.matcher(name).matches() && !FRONTEND_EXCLUDED.matcher(name).matches()) {
                        if (name.toLowerCase(Locale.ROOT).contains("apicalls")) {
                            apiCallFiles.add(relative);
                        } else {
                            frontendSources.add(relative);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Okunamayan tek bir dosya/dizin taramayı durdurmaz (NFR-06)
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Repo taranamadı: " + root, e);
        }

        Comparator<Path> byPath = Comparator.comparing(p -> p.toString().replace('\\', '/'));
        javaSources.sort(byPath);
        appConfigs.sort(byPath);
        apiCallFiles.sort(byPath);
        frontendSources.sort(byPath);
        return new ScannedRepo(repo, javaSources, appConfigs, apiCallFiles, frontendSources);
    }

    /** {@code src/test/...}, bağımlılık dizinleri ve {@code src/} dışındaki build çıktıları atlanır. */
    private static boolean isSkipped(Path relativeDir) {
        String name = relativeDir.getFileName().toString();
        if (SKIPPED_DIRS.contains(name)) {
            return true;
        }
        int count = relativeDir.getNameCount();
        boolean underSrc = false;
        for (int i = 0; i < count - 1; i++) {
            underSrc |= relativeDir.getName(i).toString().equals("src");
        }
        if (OUTPUT_DIRS.contains(name)) {
            return !underSrc;
        }
        return name.equals("test") && count >= 2 && relativeDir.getName(count - 2).toString().equals("src");
    }
}
