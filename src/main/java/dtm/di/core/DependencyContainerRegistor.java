package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.prototypes.RegistrationFunction;
import dtm.di.prototypes.async.AsyncRegistrationFunction;

/**
 * Interface para registro e remoção de dependências no contêiner.
 *
 * Define métodos para registrar dependências diretamente ou via funções de registro,
 * além de permitir a remoção de dependências por classe.
 */
public interface DependencyContainerRegistor {

    /**
     * Registra uma instância de dependência com um qualificadora específica.
     *
     * @param dependency objeto da dependência a ser registrado
     * @param qualifier  string qualificadora para diferenciar múltiplas implementações
     * @throws InvalidClassRegistrationException se houver erro no registro da dependência
     */
    void registerDependency(Object dependency, String qualifier) throws InvalidClassRegistrationException;
    /**
     * Registra uma instância de dependência com qualificadora padrão.
     *
     * @param dependency objeto da dependência a ser registrado
     * @throws InvalidClassRegistrationException se houver erro no registro da dependência
     */
    void registerDependency(Object dependency) throws InvalidClassRegistrationException;

    /**
     * Registra uma dependência através de uma função de registro personalizada.
     *
     * @param <T>                 tipo da dependência a ser registrada
     * @param registrationFunction função que provê a criação ou obtenção da dependência
     * @throws InvalidClassRegistrationException se houver erro no registro da dependência
     */
    <T> void registerDependency(RegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException;

    /**
     * Registra uma dependência asincrona de uma função de registro.
     *
     * @param <T>                 tipo da dependência a ser registrada
     * @param registrationFunction função que provê a criação ou obtenção da dependência asincrona
     * @throws InvalidClassRegistrationException se houver erro no registro da dependência
     */
    <T> void registerDependency(AsyncRegistrationFunction<T> registrationFunction) throws InvalidClassRegistrationException;


    /**
     * Remove o registro da dependência associada à classe fornecida.
     *
     * @param dependency classe da dependência a ser removida
     */
    void unRegisterDependency(Class<?> dependency);

}
