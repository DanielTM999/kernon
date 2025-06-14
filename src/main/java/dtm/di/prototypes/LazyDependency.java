package dtm.di.prototypes;

import dtm.di.exceptions.LazyDependencyException;

import java.util.concurrent.TimeUnit;

/**
 * Representa uma dependência que pode ser resolvida de forma preguiçosa (lazy),
 * ou seja, que pode não estar imediatamente disponível, mas pode ser aguardada
 * por um determinado tempo para se tornar disponível.
 *
 * @param <T> o tipo da dependência gerenciada.
 */
public interface LazyDependency<T> {
    /**
     * Retorna a instância da dependência imediatamente, ou {@code null} se ainda não estiver disponível.
     *
     * @return a instância da dependência ou {@code null} se não presente.
     */
    T get();

    /**
     * Aguarda pela disponibilidade da dependência até o tempo limite especificado,
     * retornando {@code null} se o tempo expirar sem que a dependência esteja disponível.
     *
     * @param timeout o tempo máximo para aguardar.
     * @param unit    a unidade de tempo para o parâmetro {@code timeout}.
     * @return a instância da dependência, ou {@code null} se o tempo esgotar.
     */
    T awaitOrNull(long timeout, TimeUnit unit);

    /**
     * Aguarda pela disponibilidade da dependência até o tempo limite especificado,
     * lançando exceção caso o tempo expire sem que a dependência esteja disponível.
     *
     * @param timeout o tempo máximo para aguardar.
     * @param unit    a unidade de tempo para o parâmetro {@code timeout}.
     * @return a instância da dependência.
     * @throws LazyDependencyException se o tempo esgotar sem que a dependência esteja disponível.
     */
    T awaitOrThrow(long timeout, TimeUnit unit) throws LazyDependencyException;

    /**
     * Verifica se a dependência está disponível no momento.
     *
     * @return {@code true} se a dependência estiver presente, {@code false} caso contrário.
     */
    boolean isPresent();
}
