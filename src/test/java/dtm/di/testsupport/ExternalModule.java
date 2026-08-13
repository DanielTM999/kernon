package dtm.di.testsupport;

import dtm.di.annotations.Component;

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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ExternalModule implements AutoCloseable {

    private final Path outputDirectory;
    private URLClassLoader classLoader;

    private ExternalModule(Path outputDirectory, URLClassLoader classLoader) {
        this.outputDirectory = outputDirectory;
        this.classLoader = classLoader;
    }

    public static ExternalModule compile(String name, Map<String, String> sources) {
        try {
            Path root = Files.createTempDirectory("kernon-external-" + name + "-");
            Path sourceDirectory = Files.createDirectories(root.resolve("src"));
            Path outputDirectory = Files.createDirectories(root.resolve("classes"));

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
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory.toFile()));
                fileManager.setLocation(StandardLocation.CLASS_PATH, compilationClasspath());
                fileManager.setLocation(StandardLocation.SOURCE_PATH, List.of(sourceDirectory.toFile()));

                Boolean result = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, units).call();

                if (!Boolean.TRUE.equals(result)) {
                    StringBuilder message = new StringBuilder("falha ao compilar o modulo externo " + name + ":");
                    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                        message.append(System.lineSeparator()).append(diagnostic);
                    }
                    throw new IllegalStateException(message.toString());
                }
            }

            URLClassLoader loader = new URLClassLoader(
                    name,
                    new URL[]{outputDirectory.toUri().toURL()},
                    ExternalModule.class.getClassLoader()
            );

            return new ExternalModule(root, loader);
        } catch (IOException e) {
            throw new IllegalStateException("erro ao preparar o modulo externo " + name, e);
        }
    }

    private static List<File> compilationClasspath() {
        Map<String, File> entries = new LinkedHashMap<>();
        addCodeSource(entries, Component.class);
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

    public ClassLoader classLoader() {
        return classLoader;
    }

    public Class<?> load(String className) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("classe externa nao encontrada: " + className, e);
        }
    }

    public List<Class<?>> load(String... classNames) {
        List<Class<?>> classes = new ArrayList<>();
        for (String className : classNames) {
            classes.add(load(className));
        }
        return classes;
    }

    @Override
    public void close() {
        try {
            if (classLoader != null) {
                classLoader.close();
            }
        } catch (IOException ignored) {
            classLoader = null;
        } finally {
            classLoader = null;
            deleteRecursively(outputDirectory);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ExternalModule::deleteQuietly);
        } catch (IOException ignored) {
            deleteQuietly(root);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
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
