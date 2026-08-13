package dtm.di.storage.external;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExternalLoadBatch {

    private final Map<Class<?>, ExternalComponentRegistration> registrations = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator;

    public ExternalLoadBatch(AtomicLong sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    public ExternalComponentRegistration registrationFor(Class<?> ownerClass) {
        return registrations.computeIfAbsent(
                ownerClass,
                owner -> new ExternalComponentRegistration(owner, sequenceGenerator.incrementAndGet())
        );
    }

    public List<ExternalComponentRegistration> inCreationOrder() {
        List<ExternalComponentRegistration> ordered = new ArrayList<>(registrations.values());
        ordered.sort(Comparator.comparingLong(ExternalComponentRegistration::getSequence));
        return ordered;
    }

    public List<ExternalComponentRegistration> inReverseCreationOrder() {
        List<ExternalComponentRegistration> ordered = inCreationOrder();
        java.util.Collections.reverse(ordered);
        return ordered;
    }
}
