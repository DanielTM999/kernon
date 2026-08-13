package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;

import java.util.Collection;

/**
 * Interface principal para um contêiner de dependências.
 *
 * Define operações para registrar, configurar e obter dependências,
 * além de controlar o ciclo de vida do carregamento do contêiner.
 *
 * Estende as interfaces:
 * <ul>
 *   <li>{@link DependencyContainerGetter} - para obtenção de dependências;</li>
 *   <li>{@link DependencyContainerRegistor} - para registro de dependências;</li>
 *   <li>{@link DependencyContainerConfigurator} - para configuração do contêiner.</li>
 * </ul>
 */
public interface DependencyContainer extends
        DependencyContainerGetter,
        DependencyContainerRegistor,
        DependencyContainerConfigurator
{
    /**
     * Carrega o contêiner, registrando todas as dependências necessárias.
     *
     * @throws InvalidClassRegistrationException se ocorrer erro ao registrar alguma dependência.
     */
    void load() throws InvalidClassRegistrationException;

    /**
     * Carrega de forma incremental um conjunto de classes já descobertas e carregadas pelo chamador.
     *
     * <p>Nenhuma varredura de pacotes é executada: a coleção recebida é a fronteira do
     * carregamento. As classes podem pertencer a qualquer {@link ClassLoader}, inclusive um
     * classloader que não é visível pelo classloader que carregou o contêiner.</p>
     *
     * <p>Dentro da coleção são consideradas:</p>
     * <ul>
     *   <li>classes concretas anotadas ou meta-anotadas com {@code @Component} (incluindo
     *       especializações como {@code @Service});</li>
     *   <li>classes {@code @Configuration} e seus métodos produtores de beans;</li>
     *   <li>{@code @Qualifier}, {@code @Primary}, {@code @Profile}, {@code @Singleton},
     *       {@code @Async}, {@code @Value}, {@code @PostCreation}, injeção por construtor e por
     *       campo, {@code LazyDependency}, {@code CompositeDependency}, {@code AsyncComponent},
     *       {@code RegistrationFunction} e {@code AsyncRegistrationFunction}, seguindo as mesmas
     *       regras do carregamento principal.</li>
     * </ul>
     *
     * <p>Instâncias singleton externas com métodos {@code @EventListener} têm seus listeners
     * registrados dinamicamente no {@code EventPublisher} — sem exigir {@code @Event} na classe,
     * já que a instância criada está em mãos — e removidos no descarregamento.</p>
     *
     * <p>Elementos que não são componentes — interfaces, enums, anotações, records, classes
     * abstratas ou classes sem {@code @Component}/{@code @Configuration} — são ignorados.
     * Classes duplicadas são processadas uma única vez e classes já carregadas externamente
     * resultam em no-op. A identidade usada é o próprio {@link Class}, portanto duas classes
     * de mesmo nome carregadas por classloaders diferentes são distintas.</p>
     *
     * <p>A operação é transacional: se qualquer etapa falhar, as tarefas assíncronas iniciadas
     * são canceladas, os listeners registrados são removidos, os métodos {@code @PreDestroy} das
     * instâncias já criadas são executados e todos os registros do lote são desfeitos em ordem
     * inversa, preservando integralmente o estado anterior do contêiner.</p>
     *
     * <p>O contêiner principal precisa estar carregado e permanece carregado após a chamada;
     * nenhum boot completo é executado novamente.</p>
     *
     * @param classes classes já localizadas pelo chamador; uma cópia defensiva é feita
     * @throws InvalidClassRegistrationException se o carregamento falhar; nesse caso o rollback
     *         já foi aplicado e a causa original é preservada
     * @throws NullPointerException se {@code classes} for {@code null}
     * @throws IllegalArgumentException se a coleção contiver algum elemento {@code null}
     * @throws dtm.di.exceptions.UnloadError se o contêiner principal não estiver carregado
     */
    void loadExternal(Collection<Class<?>> classes) throws InvalidClassRegistrationException;

    /**
     * Descarrega o contêiner, liberando recursos e limpando o estado.
     */
    void unload();

    /**
     * Descarrega apenas os registros externos pertencentes às classes informadas.
     *
     * <p>Remove somente o que foi criado por {@link #loadExternal(Collection)} para essas
     * classes: os slots exatos do contêiner, as entradas de {@code @Primary}, os listeners de
     * evento, as tarefas assíncronas e os caches de reflexão e de proxy. Componentes do
     * contêiner principal e outros componentes externos não são afetados, e o contêiner
     * continua carregado ({@code isLoaded() == true}).</p>
     *
     * <p>Os beans produzidos por uma classe {@code @Configuration} externa pertencem a essa
     * classe: descarregar a configuração remove todos os beans produzidos por ela.</p>
     *
     * <p>Os métodos {@code @PreDestroy} das instâncias singleton selecionadas são executados
     * uma única vez, na ordem inversa do grafo de criação. Beans prototype apenas têm a sua
     * fábrica removida — instâncias já entregues não são rastreadas pelo contêiner.</p>
     *
     * <p>Classes que não estiverem carregadas externamente são ignoradas, portanto chamadas
     * repetidas são no-op.</p>
     *
     * @param classes classes externas a descarregar; uma cópia defensiva é feita
     * @throws NullPointerException se {@code classes} for {@code null}
     * @throws IllegalArgumentException se a coleção contiver algum elemento {@code null}
     * @throws dtm.di.exceptions.ExternalDependencyInUseException se algum componente externo
     *         ainda carregado depender de uma das classes solicitadas; nenhum registro é
     *         removido nesse caso
     * @throws dtm.di.exceptions.UnloadError se o contêiner principal não estiver carregado
     */
    void unload(Collection<Class<?>> classes);

    /**
     * Indica se o contêiner está carregado e pronto para uso.
     *
     * @return true se o contêiner está carregado; false caso contrário.
     */
    boolean isLoaded();

    /**
     * Carrega dependências a partir dos arquivos localizados no diretório especificado.
     *
     * @param path caminho do diretório contendo as dependências a serem carregadas.
     */
    void loadDirectory(String path);
}
