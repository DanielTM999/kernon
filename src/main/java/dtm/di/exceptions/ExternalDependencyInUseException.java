package dtm.di.exceptions;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Getter
public class ExternalDependencyInUseException extends DependencyContainerRuntimeException {

    private final Map<Class<?>, Set<Class<?>>> dependents;

    public ExternalDependencyInUseException(Map<Class<?>, Set<Class<?>>> dependents) {
        super(buildMessage(dependents));
        this.dependents = Collections.unmodifiableMap(copy(dependents));
    }

    private static Map<Class<?>, Set<Class<?>>> copy(Map<Class<?>, Set<Class<?>>> dependents) {
        Map<Class<?>, Set<Class<?>>> copy = new LinkedHashMap<>();
        if (dependents != null) {
            for (Map.Entry<Class<?>, Set<Class<?>>> entry : dependents.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
            }
        }
        return copy;
    }

    private static String buildMessage(Map<Class<?>, Set<Class<?>>> dependents) {
        StringBuilder message = new StringBuilder("Componentes externos ainda em uso:");

        if (dependents != null) {
            for (Map.Entry<Class<?>, Set<Class<?>>> entry : dependents.entrySet()) {
                message.append(" ").append(entry.getKey().getName()).append(" <- [");
                boolean first = true;
                for (Class<?> dependent : entry.getValue()) {
                    if (!first) {
                        message.append(", ");
                    }
                    message.append(dependent.getName());
                    first = false;
                }
                message.append("];");
            }
        }

        return message.toString();
    }
}
