package me.emreihtiyar.codegraph.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Git komutu çalıştırmadan {@code .git} dizininden HEAD commit'ini okur (CodeRepo.commitHash). */
final class GitInfo {

    private GitInfo() {}

    /** Repo dizini ya da üst dizinlerinden birindeki git deposunun HEAD commit'i. */
    static Optional<String> headCommit(Path dir) {
        try {
            Optional<Path> gitDir = gitDir(dir.toAbsolutePath().normalize());
            if (gitDir.isEmpty()) {
                return Optional.empty();
            }
            String head = read(gitDir.get().resolve("HEAD"));
            if (!head.startsWith("ref:")) {
                return Optional.of(head);
            }
            String ref = head.substring(4).trim();
            Path refFile = gitDir.get().resolve(ref);
            if (Files.isRegularFile(refFile)) {
                return Optional.of(read(refFile));
            }
            // Worktree'lerde ref'ler ortak dizindedir
            Path common = gitDir.get();
            if (Files.isRegularFile(gitDir.get().resolve("commondir"))) {
                common = gitDir.get().resolve(read(gitDir.get().resolve("commondir"))).normalize();
                if (Files.isRegularFile(common.resolve(ref))) {
                    return Optional.of(read(common.resolve(ref)));
                }
            }
            Path packed = common.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.endsWith(" " + ref)) {
                        return Optional.of(line.substring(0, line.indexOf(' ')));
                    }
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> gitDir(Path dir) throws IOException {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path git = d.resolve(".git");
            if (Files.isDirectory(git)) {
                return Optional.of(git);
            }
            if (Files.isRegularFile(git)) {
                String content = read(git);
                if (content.startsWith("gitdir:")) {
                    return Optional.of(d.resolve(content.substring(7).trim()).normalize());
                }
            }
        }
        return Optional.empty();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }
}
