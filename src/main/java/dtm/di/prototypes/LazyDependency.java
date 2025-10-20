package dtm.di.prototypes;

import dtm.di.exceptions.LazyDependencyException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
     * Retorna a instância da dependência imediatamente, lançando exceção
     * caso a dependência ainda não esteja disponível.
     *
     * <p>Este método é útil quando a dependência é obrigatória e não pode
     * ser {@code null}. Se a dependência ainda não estiver resolvida,
     * ele lança uma {@link LazyDependencyException} (ou outra exceção interna
     * dependendo da implementação).</p>
     *
     * @return a instância da dependência
     * @throws LazyDependencyException se a dependência não estiver disponível no momento
     */
    T require();

    /**
     * Retorna a instância da dependência imediatamente, ou lança uma exceção
     * personalizada fornecida pelo {@link Supplier} caso a dependência não
     * esteja disponível.
     *
     * <p>Permite ao chamador definir qual exceção lançar em caso de ausência
     * da dependência, ao invés de usar a exceção padrão. O {@link Supplier}
     * só será invocado se a dependência não estiver presente.</p>
     *
     * @param <E> o tipo da exceção que será lançada se a dependência não estiver disponível
     * @param onErrorThrow fornecedor da exceção a ser lançada se a dependência não estiver presente
     * @return a instância da dependência
     * @throws E a exceção fornecida pelo {@link Supplier} se a dependência não estiver presente
     */
    <E extends Throwable> T require(Supplier<E> onErrorThrow) throws E;
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
