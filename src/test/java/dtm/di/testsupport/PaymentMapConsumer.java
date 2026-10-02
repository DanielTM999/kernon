package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Inject;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;
import dtm.di.prototypes.LazyDependency;

import java.util.Map;

@Singleton
@Component
@Profile("beanmap")
public class PaymentMapConsumer {

    private final Map<String, PaymentMethod> byConstructor;

    @Inject
    private Map<String, PaymentMethod> byField;

    @Inject
    private LazyDependency<Map<String, PaymentMethod>> lazyMap;

    @Inject
    private Map<String, RefundPolicy> emptyMap;

    public PaymentMapConsumer(Map<String, PaymentMethod> byConstructor) {
        this.byConstructor = byConstructor;
    }

    public Map<String, PaymentMethod> byConstructor() {
        return byConstructor;
    }

    public Map<String, PaymentMethod> byField() {
        return byField;
    }

    public LazyDependency<Map<String, PaymentMethod>> lazyMap() {
        return lazyMap;
    }

    public Map<String, RefundPolicy> emptyMap() {
        return emptyMap;
    }
}
