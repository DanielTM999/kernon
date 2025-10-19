package dtm.di.core;

import dtm.di.exceptions.NewInstanceException;
import dtm.di.prototypes.Dependency;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface responsável por fornecer acesso às dependências registradas no contêiner.
 *
 * Define métodos para obtenção, criação de novas instâncias com injeção de dependências,
 * além de permitir acesso à lista de dependências registradas e às classes carregadas no sistema.
 */
public interface DependencyContainerGetter {
    /**
     * Obtém uma instância da dependência associada à classe de referência.
     *
     * @param <T>       tipo da dependência esperada
     * @param reference classe que representa o tipo da dependência
     * @return instância da dependência
     */
    <T> T getDependency(Class<T> reference);
    /**
     * Obtém uma instância da dependência associada à classe de referência e qualificadora específica.
     *
     * @param <T>       tipo da dependência esperada
     * @param reference classe que representa o tipo da dependência
     * @param qualifier qualificadora que diferencia múltiplas implementações da mesma classe
     * @return instância da dependência qualificada
     */
    <T> T getDependency(Class<T> reference, String qualifier);

    /**
     * Obtém uma Lista de instância da dependência associada à classe de referência e qualificadora específica.
     *
     * @param <T>       tipo da dependência esperada
     * @param reference classe que representa o tipo da dependência
     * @return Lista instância da dependência qualificada
     */
    <T> List<T> getDependencyList(Class<T> reference);

    /**
     * Retorna um mapa contendo todas as instâncias gerenciadas cujo tipo é atribuível a partir do tipo fornecido.
     *
     * <p>Este método busca todas as instâncias que são do tipo especificado ou de subtipos dele,
     * e as retorna mapeadas pela sua classe concreta.</p>
     *
     * @param <T>             Tipo base para busca das instâncias.
     * @param <S>             Tipo concreto da instância, que deve estender ou implementar T.
     * @param assignableClass Classe base usada como filtro para as instâncias a serem retornadas.
     * @return Mapa com as classes concretas como chave e as instâncias correspondentes como valor.
     *         O mapa estará vazio se nenhuma instância compatível for encontrada.
     * @throws IllegalArgumentException Se o parâmetro {@code assignableClass} for {@code null}.
     */
    <T, S extends T> Map<Class<S>, S> getInstancesByClass(Class<T> assignableClass);

    /**
     * Cria uma nova instância da classe especificada, injetando as dependências automaticamente.
     *
     * @param <T>            tipo da instância a ser criada
     * @param referenceClass  classe da instância a ser criada
     * @return nova instância da classe com dependências injetadas
     * @throws NewInstanceException se ocorrer erro na criação da instância
     */
    <T> T newInstance(Class<T> referenceClass) throws NewInstanceException;
    /**
     * Cria uma nova instância da classe especificada, utilizando os argumentos do construtor fornecidos,
     * e injetando as dependências automaticamente.
     *
     * @param <T>            tipo da instância a ser criada
     * @param referenceClass  classe da instância a ser criada
     * @param contructorArgs  argumentos a serem passados para o construtor
     * @return nova instância da classe com dependências injetadas
     * @throws NewInstanceException se ocorrer erro na criação da instância
     */
    <T> T newInstance(Class<T> referenceClass, Object... contructorArgs) throws NewInstanceException;

    /**
     * Injeta as dependências necessárias na instância fornecida.
     *
     * @param instance objeto onde as dependências serão injetadas
     */
    void injectDependencies(Object instance);

    /**
     * Retorna a lista de dependências registradas no contêiner.
     *
     * @return lista com as dependências registradas
     */
    List<Dependency> getRegisteredDependencies();

    /**
     * Retorna o conjunto de classes carregadas pelo sistema no contêiner.
     *
     * @return conjunto de classes carregadas
     */
    Set<Class<?>> getLoadedSystemClasses();
}
