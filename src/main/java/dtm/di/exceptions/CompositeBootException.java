package dtm.di.exceptions;

import dtm.di.exceptions.boot.InvalidBootException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class CompositeBootException extends InvalidBootException {

    private final List<Throwable> errors = new CopyOnWriteArrayList<>();

    public CompositeBootException() {
        super("Múltiplas falhas detectadas durante o bootstrap da aplicação.");
    }

    public CompositeBootException(String message) {
        super(message);
    }

    public void addError(Throwable error) {
        if (error != null) {
            this.errors.add(error);
        }
    }

    public List<Throwable> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public boolean sizeIsMoreThan(int count) {
        return errors.size() > count;
    }

    public boolean hasMultipleErrors() {
        return sizeIsMoreThan(1);
    }

    public int getErrorsSize() {
        return errors.size();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasError(Class<? extends Throwable> type) {
        return errors.stream().anyMatch(type::isInstance);
    }

    public Throwable getFirstError() {
        if (errors.isEmpty()) return null;
        return errors.getFirst();
    }

    public Throwable getLastError() {
        if (errors.isEmpty()) return null;
        return errors.getLast();
    }

    public Throwable getAnyError() {
        return errors.stream().findAny().orElse(null);
    }

    @Override
    public String getMessage() {
        if (errors.isEmpty()) {
            return super.getMessage();
        }

        String detailedErrors = errors.stream()
                .map(e -> String.format("[%s]: %s", e.getClass().getSimpleName(), e.getMessage()))
                .collect(Collectors.joining("\n  -> "));

        return super.getMessage() + "\nErros acumulados:\n  -> " + detailedErrors;
    }

}
