package dtm.di.testsupport;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

public final class PerfJar implements AutoCloseable {

    private final Path root;
    private final Path jarFile;

    private PerfJar(Path root, Path jarFile) {
        this.root = root;
        this.jarFile = jarFile;
    }

    public static PerfJar fromClasses(String name, String classesDirectory) {
        try {
            Path root = Files.createTempDirectory("kernon-perfjar-" + name + "-");
            Path folder = Files.createDirectories(root.resolve("plugins"));
            Path jarFile = folder.resolve(name + ".jar");

            packageJar(Path.of(classesDirectory), jarFile);

            return new PerfJar(root, jarFile);
        } catch (IOException e) {
            throw new IllegalStateException("erro ao empacotar o jar de teste " + name, e);
        }
    }

    public static PerfJar build(String name, Map<String, String> sources) {
        try {
            Path root = Files.createTempDirectory("kernon-perfjar-" + name + "-");
            Path classes = Files.createDirectories(root.resolve("classes"));
            Path folder = Files.createDirectories(root.resolve("plugins"));

            compile(name, sources, classes);

            Path jarFile = folder.resolve(name + ".jar");
            packageJar(classes, jarFile);

            return new PerfJar(root, jarFile);
        } catch (IOException e) {
            throw new IllegalStateException("erro ao preparar o jar de teste " + name, e);
        }
    }

    public String folder() {
        return jarFile.getParent().toAbsolutePath().toString();
    }

    public String jarPath() {
        return jarFile.toAbsolutePath().toString();
    }

    private static void compile(String name, Map<String, String> sources, Path output) throws IOException {
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            units.add(new InMemorySource(entry.getKey(), entry.getValue()));
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("compilador java indisponivel neste JDK");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, compilationClasspath());

            Boolean result = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, units).call();

            if (!Boolean.TRUE.equals(result)) {
                StringBuilder message = new StringBuilder("falha ao compilar o jar de teste " + name + ":");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    message.append(System.lineSeparator()).append(diagnostic);
                }
                throw new IllegalStateException(message.toString());
            }
        }
    }

    private static void packageJar(Path classes, Path jarFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(out);
             Stream<Path> paths = Files.walk(classes)) {

            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();

            for (Path file : files) {
                String entryName = classes.relativize(file).toString().replace(File.separatorChar, '/');
                jar.putNextEntry(new JarEntry(entryName));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
    }

    private static List<File> compilationClasspath() {
        Map<String, File> entries = new LinkedHashMap<>();
        addCodeSource(entries, dtm.di.annotations.Component.class);
        addCodeSource(entries, Probe.class);
        return new ArrayList<>(entries.values());
    }

    private static void addCodeSource(Map<String, File> entries, Class<?> reference) {
        try {
            URI location = reference.getProtectionDomain().getCodeSource().getLocation().toURI();
            File file = new File(location);
            entries.putIfAbsent(file.getAbsolutePath(), file);
        } catch (Exception e) {
            throw new IllegalStateException("nao foi possivel resolver o classpath de " + reference, e);
        }
    }

    @Override
    public void close() {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String code;

        private InMemorySource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
