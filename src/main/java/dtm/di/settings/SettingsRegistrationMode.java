package dtm.di.settings;

/**
 * Define como uma fonte externa é combinada com as configurações existentes.
 */
public enum SettingsRegistrationMode {
    /** Mantém propriedades que já existem e adiciona somente as ausentes. */
    KEEP,

    /** Substitui propriedades existentes pelos valores da nova fonte. */
    OVERRIDE
}
