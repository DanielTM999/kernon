package dtm.di.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerfFixtures {

    public static final String MARKER = "perf.PerfMarker";
    public static final String CONFIGURATION = "perf.PerfConfiguration";
    public static final String BEAN_WITH_SERVICE = "perf.PerfBeanWithService";
    public static final String GENERIC_PROCESSOR = "perf.PerfProcessor";
    public static final String ASYNC_GENERIC_CONFIGURATION = "perf.PerfAsyncGenericConfiguration";

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

    public static String payloadName(int index) {
        return "perf.PerfPayload" + index;
    }

    public static String processorName(int index) {
        return "perf.PerfProcessor" + index;
    }

    public static String genericConsumerName(int index) {
        return "perf.PerfGenericConsumer" + index;
    }

    public static List<String> genericNames(int genericPairs) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < genericPairs; index++) {
            names.add(processorName(index));
            names.add(genericConsumerName(index));
        }
        return names;
    }

    public static String asyncConsumerName(int index) {
        return "perf.PerfAsyncConsumer" + index;
    }

    public static List<String> asyncNames(int asyncPairs) {
        List<String> names = new ArrayList<>();
        names.add(ASYNC_GENERIC_CONFIGURATION);
        for (int index = 0; index < asyncPairs; index++) {
            names.add(asyncConsumerName(index));
        }
        return names;
    }

    public static Map<String, String> sources(int layers, int perLayer, int configBeans) {
        return sources(layers, perLayer, configBeans, 0);
    }

    public static Map<String, String> sources(int layers, int perLayer, int configBeans, int genericPairs) {
        return sources(layers, perLayer, configBeans, genericPairs, 0);
    }

    public static Map<String, String> sources(int layers, int perLayer, int configBeans, int genericPairs, int asyncPairs) {
        Map<String, String> sources = new LinkedHashMap<>();

        if (genericPairs > 0) {
            sources.putAll(genericSources(genericPairs));
        }

        if (asyncPairs > 0) {
            sources.putAll(asyncGenericSources(asyncPairs));
        }

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

    private static Map<String, String> asyncGenericSources(int asyncPairs) {
        Map<String, String> sources = new LinkedHashMap<>();

        StringBuilder producers = new StringBuilder();
        for (int index = 0; index < asyncPairs; index++) {
            producers.append("""

                    @Async
                    @Component
                    public PerfProcessor<PerfAsyncPayload%d> asyncProcessor%d() {
                        return () -> "async-processor-%d";
                    }
                """.formatted(index, index, index));

            sources.put("perf.PerfAsyncPayload" + index, """
                    package perf;

                    public final class PerfAsyncPayload%d {
                    }
                    """.formatted(index));

            sources.put(asyncConsumerName(index), """
                    package perf;

                    import dtm.di.annotations.Component;
                    import dtm.di.annotations.Inject;
                    import dtm.di.annotations.Singleton;
                    import dtm.di.prototypes.async.AsyncComponent;

                    @Singleton
                    @Component
                    public class PerfAsyncConsumer%d {

                        @Inject
                        private AsyncComponent<PerfProcessor<PerfAsyncPayload%d>> processor;

                        public AsyncComponent<PerfProcessor<PerfAsyncPayload%d>> processor() {
                            return processor;
                        }
                    }
                    """.formatted(index, index, index));
        }

        sources.put(ASYNC_GENERIC_CONFIGURATION, """
                package perf;

                import dtm.di.annotations.Async;
                import dtm.di.annotations.Component;
                import dtm.di.annotations.Configuration;

                @Configuration
                public class PerfAsyncGenericConfiguration {
                %s
                }
                """.formatted(producers.toString()));

        return sources;
    }

    private static Map<String, String> genericSources(int genericPairs) {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put(GENERIC_PROCESSOR, """
                package perf;

                public interface PerfProcessor<T> {
                    String describe();
                }
                """);

        for (int index = 0; index < genericPairs; index++) {
            sources.put(payloadName(index), """
                    package perf;

                    public final class PerfPayload%d {
                    }
                    """.formatted(index));

            sources.put(processorName(index), """
                    package perf;

                    import dtm.di.annotations.Component;
                    import dtm.di.annotations.Singleton;

                    @Singleton
                    @Component
                    public class PerfProcessor%d implements PerfProcessor<PerfPayload%d> {
                        @Override
                        public String describe() {
                            return "processor-%d";
                        }
                    }
                    """.formatted(index, index, index));

            sources.put(genericConsumerName(index), """
                    package perf;

                    import dtm.di.annotations.Component;
                    import dtm.di.annotations.Inject;
                    import dtm.di.annotations.Singleton;

                    @Singleton
                    @Component
                    public class PerfGenericConsumer%d {

                        @Inject
                        private PerfProcessor<PerfPayload%d> processor;

                        public String describe() {
                            return processor.describe();
                        }
                    }
                    """.formatted(index, index));
        }

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
