package dtm.di.core;

/**
 * Interface para configuração do comportamento do contêiner de dependências.
 *
 * Permite habilitar ou desabilitar funcionalidades específicas do contêiner,
 * como o registro de dependências filhas, injeção paralela e suporte a AOP.
 */
public interface DependencyContainerConfigurator {
    /**
     * Habilita o registro automático de dependências filhas.
     *
     * Quando habilitado, o contêiner também registra dependências associadas
     * às subclasses ou dependências relacionadas automaticamente.
     */
    void enableChildrenRegistration();
    /**
     * Desabilita o registro automático de dependências filhas.
     */
    void disableChildrenRegistration();

    /**
     * Habilita a injeção de dependências em paralelo.
     *
     * Isso pode melhorar o desempenho ao injetar dependências em múltiplos objetos simultaneamente,
     * utilizando threads paralelas.
     */
    void enableParallelInjection();
    /**
     * Desabilita a injeção de dependências em paralelo.
     */
    void disableParallelInjection();

    /**
     * Habilita o suporte a Aspect-Oriented Programming (AOP).
     *
     * Permite a aplicação de proxies e interceptadores para adicionar comportamentos
     * transversais (como logging, transações, segurança) às dependências.
     */
    void enableAOP();
    /**
     * Desabilita o suporte a Aspect-Oriented Programming (AOP).
     */
    void disableAOP();
}
