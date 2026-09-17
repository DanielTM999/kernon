package dtm.di.exceptions;

import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Collectors;

public class AmbiguousDependencyException extends DependencyInjectionException {

    private final transient Type requested;
    private final String qualifier;
    private final transient List<Class<?>> candidates;
    private final String origin;

    public AmbiguousDependencyException(Type requested, String qualifier, List<Class<?>> candidates, String origin) {
        super(buildMessage(requested, qualifier, candidates, origin));
        this.requested = requested;
        this.qualifier = qualifier;
        this.candidates = List.copyOf(candidates);
        this.origin = origin;
    }

    public Type getRequested() {
        return requested;
    }

    public String getQualifier() {
        return qualifier;
    }

    public List<Class<?>> getCandidates() {
        return candidates;
    }

    public String getOrigin() {
        return origin;
    }

    private static String buildMessage(Type requested, String qualifier, List<Class<?>> candidates, String origin) {
        String candidateNames = candidates.stream()
                .map(candidate -> (candidate != null) ? candidate.getName() : "desconhecido")
                .sorted()
                .collect(Collectors.joining(", "));

        StringBuilder message = new StringBuilder("Mais de um bean candidato para ")
                .append((requested != null) ? requested.getTypeName() : "tipo desconhecido")
                .append(" (qualifier='")
                .append(qualifier)
                .append("'): ")
                .append(candidateNames)
                .append(". Use @Qualifier, @Primary ou um tipo generico mais especifico.");

        if (origin != null && !origin.isBlank()) {
            message.append(" Origem: ").append(origin).append(".");
        }

        return message.toString();
    }
}
