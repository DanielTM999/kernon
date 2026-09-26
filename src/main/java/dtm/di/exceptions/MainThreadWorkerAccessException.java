package dtm.di.exceptions;

import java.util.Locale;

public class MainThreadWorkerAccessException extends RuntimeException {

    public enum Reason {
        NOT_ENABLED,
        STATIC_ACCESS_DISABLED
    }

    private final Reason reason;

    public MainThreadWorkerAccessException(Reason reason) {
        super(determineMessage(reason));
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    private static String determineMessage(Reason reason) {
        boolean portuguese = Locale.getDefault().getLanguage().equals("pt");

        if (reason == Reason.STATIC_ACCESS_DISABLED) {
            return portuguese
                    ? "Acesso estático ao MainThreadWorker desabilitado: utilize @EnableMainThreadWorker(staticCaller = true) " +
                    "na classe bootable ou obtenha o MainThreadWorker via injeção de dependência."
                    : "Static access to MainThreadWorker is disabled: use @EnableMainThreadWorker(staticCaller = true) " +
                    "on the bootable class or obtain the MainThreadWorker through dependency injection.";
        }

        return portuguese
                ? "MainThreadWorker indisponível: a aplicação não foi iniciada por 'ManagedApplication.doRun' " +
                "ou a classe bootable não está anotada com @EnableMainThreadWorker."
                : "MainThreadWorker unavailable: the application was not started through 'ManagedApplication.doRun' " +
                "or the bootable class is not annotated with @EnableMainThreadWorker.";
    }
}
