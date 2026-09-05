package dtm.di.storage;

import dtm.di.settings.AppSettings;
import dtm.di.settings.JsonAppSettings;
import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;
import dtm.discovery.stereotips.ClassFinderStereotips;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Slf4j
public class ClassFinderConfigurationsStorage implements ClassFinderConfigurations {

    private static final String ALL_ELEMENTS_KEY = "allElements";
    private static final String ANONYMOUS_CLASSES_KEY = "anonymousClasses";
    private static final String IGNORE_SUB_JARS_KEY = "ignoreSubJars";
    private static final String IGNORE_PACKAGES_KEY = "ignorePackages";
    private static final String IGNORE_JARS_TERMS_KEY = "ignoreJarsTerms";

    private final Set<String> ignoredResourcePaths;
    private final boolean allElements;
    private final boolean anonimousClass;
    private final boolean ignoreSubJars;
    private final List<String> ignorePackages;
    private final List<String> ignoreJarsTerms;
    private final ClassFinderErrorHandler handler;
    private final Class<? extends Annotation> filterByAnnotation;

    public ClassFinderConfigurationsStorage(
            boolean allElements,
            boolean anonimousClass,
            boolean ignoreSubJars,
            List<String> ignorePackages,
            List<String> ignoreJarsTerms,
            ClassFinderErrorHandler handler,
            Class<? extends Annotation> filterByAnnotation
    ) {
        this.allElements = allElements;
        this.anonimousClass = anonimousClass;
        this.ignoreSubJars = ignoreSubJars;
        this.ignorePackages = new ArrayList<>(ignorePackages);
        this.ignoreJarsTerms = new ArrayList<>(ignoreJarsTerms);
        this.handler = handler != null ? handler : (err) -> {
            log.error("Class Scanner Error", err);
        };
        this.filterByAnnotation = filterByAnnotation;
        this.ignoredResourcePaths = Set.of();
    }

    private ClassFinderConfigurationsStorage(ConfigData data, Set<String> ignoredResourcePaths) {
        this.allElements = data.allElements;
        this.anonimousClass = data.anonimousClass;
        this.ignoreSubJars = data.ignoreSubJars;
        this.ignorePackages = new ArrayList<>(data.ignorePackages);
        this.ignoreJarsTerms = new ArrayList<>(data.ignoreJarsTerms);
        this.handler = data.handler != null ? data.handler : (err) -> {
            log.error("Class Scanner Error", err);
        };
        this.filterByAnnotation = data.filterByAnnotation;
        this.ignoredResourcePaths = ignoredResourcePaths;
    }

    private static Set<String> toResourcePaths(List<String> packages) {
        Set<String> paths = new LinkedHashSet<>();
        for (String candidate : packages) {
            if (candidate == null || candidate.isBlank()) continue;
            paths.add("/" + candidate.trim().replace('.', '/'));
        }
        return Collections.unmodifiableSet(paths);
    }

    @Override
    public Predicate<ClassFinderStereotips> getAceptHandler() {
        if (ignoredResourcePaths.isEmpty()) {
            return stereotips -> true;
        }
        return this::isScannable;
    }

    private boolean isScannable(ClassFinderStereotips stereotips) {
        if (stereotips == null) return true;
        URL url = stereotips.getArchiverUrl();
        if (url == null) return true;

        String path = url.getPath().replace('\\', '/');
        for (String ignored : ignoredResourcePaths) {
            if (path.endsWith(ignored) || path.contains(ignored + "/")) return false;
        }
        return true;
    }

    public ClassFinderConfigurationsStorage() {
        this(createDefaults());
    }

    public static ClassFinderConfigurationsStorage fromSettings(AppSettings settings) {
        ConfigData defaults = createDefaults();
        if (settings == null || !settings.has(JsonAppSettings.CLASS_SCAN_PROPERTY)) {
            return new ClassFinderConfigurationsStorage(defaults);
        }

        String prefix = JsonAppSettings.CLASS_SCAN_PROPERTY + ".";
        defaults.allElements = settings.getBoolean(prefix + ALL_ELEMENTS_KEY, defaults.allElements);
        defaults.anonimousClass = settings.getBoolean(prefix + ANONYMOUS_CLASSES_KEY, defaults.anonimousClass);
        defaults.ignoreSubJars = settings.getBoolean(prefix + IGNORE_SUB_JARS_KEY, defaults.ignoreSubJars);

        List<String> configuredPackages = readList(settings, prefix + IGNORE_PACKAGES_KEY);
        appendAll(defaults.ignorePackages, configuredPackages);
        appendAll(defaults.ignoreJarsTerms, readList(settings, prefix + IGNORE_JARS_TERMS_KEY));

        log.info(
                "Scan de classes configurado por '{}': anonymousClasses={}, ignorePackages={}",
                JsonAppSettings.CLASS_SCAN_PROPERTY,
                defaults.anonimousClass,
                configuredPackages
        );
        return new ClassFinderConfigurationsStorage(defaults, toResourcePaths(configuredPackages));
    }

    private static List<String> readList(AppSettings settings, String key) {
        if (!settings.has(key)) return List.of();
        String[] values = settings.getObject(key, String[].class);
        if (values == null || values.length == 0) return List.of();
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static void appendAll(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) target.add(value);
        }
    }

    private static ConfigData createDefaults() {
        ClassFinderConfigurations defaults = new ClassFinderConfigurations() {};

        List<String> packages = Collections.synchronizedList(new ArrayList<>(defaults.getIgnorePackges()));
        packages.add("net.bytebuddy");
        packages.add("ch.qos.logback");
        packages.add("lombok");

        List<String> jars = Collections.synchronizedList(new ArrayList<>(defaults.getIgnoreJarsTerms()));
        jars.add("lombok");
        jars.add("byte-buddy");
        jars.add("logback-classic");
        jars.add("slf4j-api");
        jars.add("classfinder");

        return new ConfigData(
                false,
                true,
                true,
                packages,
                jars,
                null,
                null
        );
    }

    private ClassFinderConfigurationsStorage(ConfigData data) {
        this(
                data.allElements,
                data.anonimousClass,
                data.ignoreSubJars,
                data.ignorePackages,
                data.ignoreJarsTerms,
                data.handler,
                data.filterByAnnotation
        );
    }

    @Override
    public boolean getAllElements() {
        return allElements;
    }

    @Override
    public boolean getAnonimousClass() {
        return anonimousClass;
    }

    @Override
    public boolean ignoreSubJars() {
        return ignoreSubJars;
    }

    @Override
    public List<String> getIgnorePackges() {
        return ignorePackages;
    }

    @Override
    public List<String> getIgnoreJarsTerms() {
        return ignoreJarsTerms;
    }

    @Override
    public ClassFinderErrorHandler getErrorHandler() {
        return handler;
    }

    @Override
    public Class<? extends Annotation> getFilterByAnnotation() {
        return filterByAnnotation;
    }

    private static class ConfigData {
        boolean allElements;
        boolean anonimousClass;
        boolean ignoreSubJars;
        List<String> ignorePackages;
        List<String> ignoreJarsTerms;
        ClassFinderErrorHandler handler;
        Class<? extends Annotation> filterByAnnotation;

        ConfigData(boolean allElements,
                   boolean anonimousClass,
                   boolean ignoreSubJars,
                   List<String> ignorePackages,
                   List<String> ignoreJarsTerms,
                   ClassFinderErrorHandler handler,
                   Class<? extends Annotation> filterByAnnotation) {
            this.allElements = allElements;
            this.anonimousClass = anonimousClass;
            this.ignoreSubJars = ignoreSubJars;
            this.ignorePackages = ignorePackages;
            this.ignoreJarsTerms = ignoreJarsTerms;
            this.handler = handler;
            this.filterByAnnotation = filterByAnnotation;
        }
    }
}

