package dtm.di.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ExternalFixtures {

    public static final String GREETER = "ext.Greeter";
    public static final String BASE_SERVICE = "ext.BaseService";
    public static final String PROTOTYPE_SERVICE = "ext.PrototypeService";
    public static final String CTOR_CONSUMER = "ext.CtorConsumer";
    public static final String FIELD_CONSUMER = "ext.FieldConsumer";
    public static final String MAIN_AWARE = "ext.MainAware";
    public static final String INTERFACE_CONSUMER = "ext.InterfaceConsumer";
    public static final String SPEAKER = "ext.Speaker";
    public static final String ALPHA_SPEAKER = "ext.AlphaSpeaker";
    public static final String BETA_SPEAKER = "ext.BetaSpeaker";
    public static final String REPORTER = "ext.Reporter";
    public static final String PRIMARY_REPORTER = "ext.PrimaryReporter";
    public static final String FALLBACK_REPORTER = "ext.FallbackReporter";
    public static final String ACTIVE_PROFILE_SERVICE = "ext.ActiveProfileService";
    public static final String INACTIVE_PROFILE_SERVICE = "ext.InactiveProfileService";
    public static final String CONFIG_BEAN = "ext.ConfigBean";
    public static final String COMPONENT_AWARE_BEAN = "ext.ComponentAwareBean";
    public static final String EXTERNAL_CONFIGURATION = "ext.ExternalConfiguration";
    public static final String ORPHAN_BEAN = "ext.OrphanBean";
    public static final String FAILING_CONFIGURATION = "ext.FailingConfiguration";
    public static final String CYCLE_A = "ext.CycleA";
    public static final String CYCLE_B = "ext.CycleB";
    public static final String BROKEN_SERVICE = "ext.BrokenService";
    public static final String PING_EVENT = "ext.PingEvent";
    public static final String EVENTFUL_SERVICE = "ext.EventfulService";
    public static final String ASYNC_SERVICE = "ext.AsyncService";
    public static final String VALUE_SERVICE = "ext.ValueService";
    public static final String DEPENDENT_SERVICE = "ext.DependentService";
    public static final String SHARED_GREETER_IMPL = "ext.SharedGreeterImpl";
    public static final String DETACHED_CONSUMER = "ext.DetachedConsumer";
    public static final String FUNCTION_BEAN = "ext.FunctionBean";
    public static final String ASYNC_FUNCTION_BEAN = "ext.AsyncFunctionBean";
    public static final String FUNCTION_CONFIGURATION = "ext.FunctionConfiguration";
    public static final String PLAIN_CLASS = "ext.PlainClass";
    public static final String ABSTRACT_COMPONENT = "ext.AbstractComponent";
    public static final String COMPONENT_ENUM = "ext.ComponentEnum";
    public static final String COMPONENT_RECORD = "ext.ComponentRecord";
    public static final String COMPONENT_ANNOTATION = "ext.ComponentAnnotation";

    private ExternalFixtures() {
    }

    public static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put(GREETER, """
                package ext;

                public interface Greeter {
                    String greet();
                }
                """);

        sources.put(BASE_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.PostCreation;
                import dtm.di.annotations.PreDestroy;
                import dtm.di.annotations.Singleton;
                import dtm.di.testsupport.Probe;

                @Singleton
                @Component
                public class BaseService implements Greeter {

                    @PostCreation
                    public void started() {
                        Probe.record("BaseService.post");
                    }

                    @PreDestroy
                    public void stopped() {
                        Probe.record("BaseService.destroy");
                    }

                    @Override
                    public String greet() {
                        return "base";
                    }
                }
                """);

        sources.put(PROTOTYPE_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;

                @Component
                public class PrototypeService {
                    public String id() {
                        return "prototype-" + System.identityHashCode(this);
                    }
                }
                """);

        sources.put(CTOR_CONSUMER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class CtorConsumer {

                    private final BaseService base;

                    public CtorConsumer(BaseService base) {
                        this.base = base;
                    }

                    public String describe() {
                        return "ctor:" + base.greet();
                    }
                }
                """);

        sources.put(FIELD_CONSUMER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class FieldConsumer {

                    @Inject
                    private BaseService base;

                    public String describe() {
                        return "field:" + base.greet();
                    }
                }
                """);

        sources.put(MAIN_AWARE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;
                import dtm.di.testsupport.MainCounter;

                @Singleton
                @Component
                public class MainAware {

                    @Inject
                    private MainCounter counter;

                    public int useMain() {
                        return counter.increment();
                    }

                    public String describe() {
                        return counter.describe();
                    }
                }
                """);

        sources.put(INTERFACE_CONSUMER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class InterfaceConsumer {

                    @Inject
                    private Greeter greeter;

                    public String describe() {
                        return "interface:" + greeter.greet();
                    }
                }
                """);

        sources.put(SPEAKER, """
                package ext;

                public interface Speaker {
                    String speak();
                }
                """);

        sources.put(ALPHA_SPEAKER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Qualifier;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Qualifier("alpha")
                public class AlphaSpeaker implements Speaker {
                    @Override
                    public String speak() {
                        return "alpha";
                    }
                }
                """);

        sources.put(BETA_SPEAKER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Qualifier;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Qualifier("beta")
                public class BetaSpeaker implements Speaker {
                    @Override
                    public String speak() {
                        return "beta";
                    }
                }
                """);

        sources.put(REPORTER, """
                package ext;

                public interface Reporter {
                    String report();
                }
                """);

        sources.put(PRIMARY_REPORTER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Primary;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Primary
                public class PrimaryReporter implements Reporter {
                    @Override
                    public String report() {
                        return "primary";
                    }
                }
                """);

        sources.put(FALLBACK_REPORTER, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Qualifier;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Qualifier("fallback")
                public class FallbackReporter implements Reporter {
                    @Override
                    public String report() {
                        return "fallback";
                    }
                }
                """);

        sources.put(ACTIVE_PROFILE_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Profile;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Profile("test")
                public class ActiveProfileService {
                    public String value() {
                        return "active";
                    }
                }
                """);

        sources.put(INACTIVE_PROFILE_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Profile;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                @Profile("disabled-profile")
                public class InactiveProfileService {
                    public String value() {
                        return "inactive";
                    }
                }
                """);

        sources.put(CONFIG_BEAN, """
                package ext;

                public class ConfigBean {

                    private final String value;

                    public ConfigBean() {
                        this("empty");
                    }

                    public ConfigBean(String value) {
                        this.value = value;
                    }

                    public String value() {
                        return value;
                    }
                }
                """);

        sources.put(COMPONENT_AWARE_BEAN, """
                package ext;

                import dtm.di.annotations.PreDestroy;
                import dtm.di.testsupport.Probe;

                public class ComponentAwareBean {

                    private final String value;

                    public ComponentAwareBean() {
                        this("empty");
                    }

                    public ComponentAwareBean(String value) {
                        this.value = value;
                    }

                    @PreDestroy
                    public void stopped() {
                        Probe.record("ComponentAwareBean.destroy");
                    }

                    public String value() {
                        return value;
                    }
                }
                """);

        sources.put(EXTERNAL_CONFIGURATION, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Configuration;
                import dtm.di.testsupport.Probe;

                @Configuration
                public class ExternalConfiguration {

                    @Component
                    public ConfigBean configBean() {
                        Probe.record("ExternalConfiguration.configBean");
                        return new ConfigBean("from-config");
                    }

                    @Component
                    public ComponentAwareBean componentAwareBean(BaseService base) {
                        Probe.record("ExternalConfiguration.componentAwareBean");
                        return new ComponentAwareBean("config:" + base.greet());
                    }
                }
                """);

        sources.put(ORPHAN_BEAN, """
                package ext;

                public class OrphanBean {
                    public String value() {
                        return "orphan";
                    }
                }
                """);

        sources.put(FAILING_CONFIGURATION, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Configuration;

                @Configuration
                public class FailingConfiguration {

                    @Component
                    public OrphanBean orphanBean() {
                        return new OrphanBean();
                    }

                    @Component
                    public ConfigBean brokenBean() {
                        throw new IllegalStateException("falha proposital no metodo produtor");
                    }
                }
                """);

        sources.put(CYCLE_A, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class CycleA {

                    @Inject
                    private CycleB other;

                    public CycleB other() {
                        return other;
                    }
                }
                """);

        sources.put(CYCLE_B, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class CycleB {

                    @Inject
                    private CycleA other;

                    public CycleA other() {
                        return other;
                    }
                }
                """);

        sources.put(BROKEN_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.Singleton;

                @Singleton
                @Component
                public class BrokenService {

                    @Inject
                    private BaseService base;

                    public BrokenService() {
                        throw new IllegalStateException("falha proposital no construtor");
                    }
                }
                """);

        sources.put(PING_EVENT, """
                package ext;

                public class PingEvent {

                    private final String message;

                    public PingEvent(String message) {
                        this.message = message;
                    }

                    public String message() {
                        return message;
                    }
                }
                """);

        sources.put(EVENTFUL_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;
                import dtm.di.annotations.event.Event;
                import dtm.di.annotations.event.EventListener;
                import dtm.di.testsupport.Probe;

                @Singleton
                @Component
                @Event
                public class EventfulService {

                    @EventListener
                    public void onPing(PingEvent event) {
                        Probe.record("EventfulService.onPing:" + event.message());
                    }
                }
                """);

        sources.put(ASYNC_SERVICE, """
                package ext;

                import dtm.di.annotations.Async;
                import dtm.di.annotations.Component;
                import dtm.di.annotations.PreDestroy;
                import dtm.di.testsupport.Probe;

                @Async
                @Component
                public class AsyncService {

                    public AsyncService() {
                        Probe.record("AsyncService.created");
                    }

                    @PreDestroy
                    public void stopped() {
                        Probe.record("AsyncService.destroy");
                    }

                    public String value() {
                        return "async";
                    }
                }
                """);

        sources.put(VALUE_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;
                import dtm.di.annotations.settings.Value;

                @Singleton
                @Component
                public class ValueService {

                    @Value(key = "external.missing.key", defaultValue = "fallback-value")
                    private String setting;

                    public String setting() {
                        return setting;
                    }
                }
                """);

        sources.put(DEPENDENT_SERVICE, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Inject;
                import dtm.di.annotations.PreDestroy;
                import dtm.di.annotations.Singleton;
                import dtm.di.testsupport.Probe;

                @Singleton
                @Component
                public class DependentService {

                    @Inject
                    private BaseService base;

                    @PreDestroy
                    public void stopped() {
                        Probe.record("DependentService.destroy");
                    }

                    public String describe() {
                        return "dependent:" + base.greet();
                    }
                }
                """);

        sources.put(SHARED_GREETER_IMPL, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Singleton;
                import dtm.di.testsupport.SharedGreeter;

                @Singleton
                @Component
                public class SharedGreeterImpl implements SharedGreeter {
                    @Override
                    public String greet() {
                        return "external-greeter";
                    }
                }
                """);

        sources.put(DETACHED_CONSUMER, """
                package ext;

                import dtm.di.annotations.Inject;

                public class DetachedConsumer {

                    @Inject
                    private BaseService base;

                    public String describe() {
                        return base == null ? "sem-base" : "detached:" + base.greet();
                    }
                }
                """);

        sources.put(FUNCTION_BEAN, """
                package ext;

                public class FunctionBean {

                    private final String value;

                    public FunctionBean(String value) {
                        this.value = value;
                    }

                    public String value() {
                        return value;
                    }
                }
                """);

        sources.put(ASYNC_FUNCTION_BEAN, """
                package ext;

                import dtm.di.testsupport.Probe;

                public class AsyncFunctionBean {

                    public AsyncFunctionBean() {
                        Probe.record("AsyncFunctionBean.created");
                    }

                    public String value() {
                        return "async-function";
                    }
                }
                """);

        sources.put(FUNCTION_CONFIGURATION, """
                package ext;

                import dtm.di.annotations.Component;
                import dtm.di.annotations.Configuration;
                import dtm.di.prototypes.RegistrationFunction;
                import dtm.di.prototypes.async.AsyncRegistrationFunction;

                import java.util.concurrent.ExecutorService;
                import java.util.function.Supplier;

                @Configuration
                public class FunctionConfiguration {

                    @Component
                    public RegistrationFunction<FunctionBean> functionBean() {
                        return new RegistrationFunction<FunctionBean>() {

                            @Override
                            public Supplier<FunctionBean> getFunction() {
                                return () -> new FunctionBean("from-function");
                            }

                            @Override
                            public Class<FunctionBean> getReferenceClass() {
                                return FunctionBean.class;
                            }

                            @Override
                            public String getQualifier() {
                                return "function";
                            }
                        };
                    }

                    @Component
                    public AsyncRegistrationFunction<AsyncFunctionBean> asyncFunctionBean() {
                        return new AsyncRegistrationFunction<AsyncFunctionBean>() {

                            @Override
                            public ExecutorService getExecutor() {
                                return null;
                            }

                            @Override
                            public Supplier<AsyncFunctionBean> getFunction() {
                                return AsyncFunctionBean::new;
                            }

                            @Override
                            public Class<AsyncFunctionBean> getReferenceClass() {
                                return AsyncFunctionBean.class;
                            }

                            @Override
                            public String getQualifier() {
                                return "asyncFunction";
                            }
                        };
                    }
                }
                """);

        sources.put(PLAIN_CLASS, """
                package ext;

                public class PlainClass {
                    public String value() {
                        return "plain";
                    }
                }
                """);

        sources.put(ABSTRACT_COMPONENT, """
                package ext;

                import dtm.di.annotations.Component;

                @Component
                public abstract class AbstractComponent {
                    public abstract String value();
                }
                """);

        sources.put(COMPONENT_ENUM, """
                package ext;

                import dtm.di.annotations.Component;

                @Component
                public enum ComponentEnum {
                    ONE,
                    TWO
                }
                """);

        sources.put(COMPONENT_RECORD, """
                package ext;

                import dtm.di.annotations.Component;

                @Component
                public record ComponentRecord(String value) {
                }
                """);

        sources.put(COMPONENT_ANNOTATION, """
                package ext;

                import dtm.di.annotations.Component;

                @Component
                public @interface ComponentAnnotation {
                }
                """);

        return sources;
    }
}
