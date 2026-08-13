package dtm.di.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerfFixtures {

    public static final String MARKER = "perf.PerfMarker";
    public static final String CONFIGURATION = "perf.PerfConfiguration";
    public static final String BEAN_WITH_SERVICE = "perf.PerfBeanWithService";

    private PerfFixtures() {
    }

    public static String serviceName(int layer, int index) {
        return "perf.Service" + layer + "_" + index;
    }

    public static String beanName(int index) {
        return "perf.PerfBean" + index;
    }

    public static List<String> serviceNames(int layers, int perLayer) {
        List<String> names = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (int index = 0; index < perLayer; index++) {
                names.add(serviceName(layer, index));
            }
        }
        return names;
    }

    public static Map<String, String> sources(int layers, int perLayer, int configBeans) {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put(MARKER, """
                package perf;

                public interface PerfMarker {
                    String id();
                }
                """);

        for (int layer = 0; layer < layers; layer++) {
            for (int index = 0; index < perLayer; index++) {
                sources.put(serviceName(layer, index), service(layer, index, perLayer));
            }
        }

        for (int index = 0; index < configBeans; index++) {
            sources.put(beanName(index), bean("PerfBean" + index));
        }

        sources.put(BEAN_WITH_SERVICE, bean("PerfBeanWithService"));
        sources.put(CONFIGURATION, configuration(configBeans));

        return sources;
    }

    private static String bean(String simpleName) {
        return """
                package perf;

                public class %s {

                    private final String value;

                    public %s() {
                        this("empty");
                    }

                    public %s(String value) {
                        this.value = value;
                    }

                    public String value() {
                        return value;
                    }
                }
                """.formatted(simpleName, simpleName, simpleName);
    }

    private static String service(int layer, int index, int perLayer) {
        String simpleName = "Service" + layer + "_" + index;
        String scope = index % 10 == 0 ? "" : "@Singleton\n";

        if (layer == 0) {
            return """
                    package perf;

                    import dtm.di.annotations.Component;
                    import dtm.di.annotations.PostCreation;
                    import dtm.di.annotations.PreDestroy;
                    import dtm.di.annotations.Singleton;

                    %s@Component
                    public class %s implements PerfMarker {

                        private int started;

                        @PostCreation
                        public void start() {
                            started++;
                        }

                        @PreDestroy
                        public void stop() {
                            started--;
                        }

                        @Override
                        public String id() {
                            return "%s";
                        }
                    }
                    """.formatted(scope, simpleName, simpleName);
        }

        String dependency = "Service" + (layer - 1) + "_" + (index % perLayer);

        return """
                package perf;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.PostCreation;
                import dtm.di.annotations.Singleton;

                %s@Component
                public class %s implements PerfMarker {

                    @Inject
                    private %s dependency;

                    private int started;

                    @PostCreation
                    public void start() {
                        started++;
                    }

                    @Override
                    public String id() {
                        return "%s->" + (dependency == null ? "null" : dependency.id());
                    }
                }
                """.formatted(scope, simpleName, dependency, simpleName);
    }

    private static String configuration(int configBeans) {
        StringBuilder methods = new StringBuilder();

        for (int index = 0; index < configBeans; index++) {
            methods.append("""

                        @Component
                        public PerfBean%d bean%d() {
                            return new PerfBean%d("bean%d");
                        }
                    """.formatted(index, index, index, index));
        }

        methods.append("""

                    @Component
                    public PerfBeanWithService beanWithService(Service0_1 service) {
                        return new PerfBeanWithService(service.id());
                    }
                """);

        return """
                package perf;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Configuration;

                @Configuration
                public class PerfConfiguration {
                %s}
                """.formatted(methods);
    }
}
