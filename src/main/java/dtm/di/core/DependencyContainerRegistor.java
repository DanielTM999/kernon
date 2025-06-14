package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;
import dtm.di.prototypes.RegistrationFunction;
import lombok.NonNull;

import java.util.function.Supplier;

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
     * Remove o registro da dependência associada à classe fornecida.
     *
     * @param dependency classe da dependência a ser removida
     */
    void unRegisterDependency(Class<?> dependency);

    /**
     * Cria uma função de registro padrão a partir de uma classe de referência e uma função Supplier.
     * A qualificadora padrão é "default".
     *
     * @param <T>       tipo da dependência
     * @param reference classe da dependência
     * @param action    Supplier que cria a instância da dependência
     * @return função de registro pronta para uso
     */
    static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action){
        return ofAction(reference, action, "default");
    }

    /**
     * Cria uma função de registro a partir de uma classe de referência, uma função Supplier e uma qualificadora.
     *
     * @param <T>       tipo da dependência
     * @param reference classe da dependência
     * @param action    Supplier que cria a instância da dependência
     * @param qualifier qualificadora para diferenciar múltiplas implementações
     * @return função de registro pronta para uso
     */
    static <T> RegistrationFunction<T> ofAction(@NonNull Class<T> reference, @NonNull Supplier<T> action, @NonNull String qualifier){
        return new RegistrationFunction<T>() {

            @NonNull
            @Override
            public Supplier<T> getFunction() {
                return action;
            }

            @NonNull
            @Override
            public Class<T> getReferenceClass() {
                return reference;
            }

            @NonNull
            @Override
            public String getQualifier() {
                return (qualifier.isEmpty()) ? "default" : qualifier;
            }

        };
    }
}
