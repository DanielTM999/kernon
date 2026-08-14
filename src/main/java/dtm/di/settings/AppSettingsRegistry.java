package dtm.di.settings;

import java.nio.file.Path;

/**
 * Registro incremental de configurações JSON externas.
 *
 * <p>Os overloads sem {@link SettingsRegistrationMode} usam
 * {@link SettingsRegistrationMode#KEEP}. Fontes recebidas como {@link String} ou
 * {@link Path} representam um único documento. Fontes recebidas como {@link Class}
 * ou {@link ClassLoader} carregam {@code settings.json} e os recursos opcionais
 * {@code settings.<profile>.json} dos profiles ativos.</p>
 */
public interface AppSettingsRegistry {

    void register(String json);

    void register(String json, SettingsRegistrationMode mode);

    void register(Path json);

    void register(Path json, SettingsRegistrationMode mode);

    void register(Class<?> source);

    void register(Class<?> source, SettingsRegistrationMode mode);

    void register(ClassLoader source);

    void register(ClassLoader source, SettingsRegistrationMode mode);
}
