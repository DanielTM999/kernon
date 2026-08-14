package dtm.di.settings;

/**
 * Indica que a política definida no settings principal bloqueou um registro externo.
 */
public class SettingsRegistrationBlockedException extends SettingsRegistrationException {

    public SettingsRegistrationBlockedException(String message) {
        super(message);
    }
}
