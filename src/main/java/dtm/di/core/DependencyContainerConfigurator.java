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

    /**
     * verifica se á suporte a Aspect-Oriented Programming (AOP).
     */
    boolean isAopEnabled();

    /**
     * Define a estratégia de injeção de dependências utilizada pelo contêiner.
     *
     * A estratégia controla como o contêiner executa o processo de injeção:
     * - Dependendo do modo selecionado, a injeção pode ser executada de forma
     *   paralela, sequencial ou adaptativa conforme o volume de dependências.
     *
     * @param strategy A estratégia de injeção a ser utilizada pelo contêiner.
     *                 Não deve ser {@code null}.
     */
    void setInjectionStrategy(InjectionStrategy strategy);

}
