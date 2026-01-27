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
