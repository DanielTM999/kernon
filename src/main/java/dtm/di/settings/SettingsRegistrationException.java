package dtm.di.settings;

/**
 * Falha ao ler, validar ou incorporar uma fonte externa de configurações.
 */
public class SettingsRegistrationException extends RuntimeException {

    public SettingsRegistrationException(String message) {
        super(message);
    }

    public SettingsRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
