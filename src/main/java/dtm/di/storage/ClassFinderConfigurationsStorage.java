package dtm.di.storage;

import dtm.discovery.core.ClassFinderConfigurations;
import dtm.discovery.core.ClassFinderErrorHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ClassFinderConfigurationsStorage implements ClassFinderConfigurations {

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
    }

    public ClassFinderConfigurationsStorage() {
        this(createDefaults());
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
                defaults.getAllElements(),
                true,
                false,
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

