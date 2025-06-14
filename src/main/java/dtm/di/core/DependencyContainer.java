package dtm.di.core;

import dtm.di.exceptions.InvalidClassRegistrationException;

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
     * Descarrega o contêiner, liberando recursos e limpando o estado.
     */
    void unload();

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
