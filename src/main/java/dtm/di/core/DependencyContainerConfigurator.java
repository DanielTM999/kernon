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
     * - A configuração programática tem precedência sobre a propriedade
     *   {@code dependencyContainer.injectionStrategy} dos settings.
     *
     * @param strategy A estratégia de injeção a ser utilizada pelo contêiner.
     *                 {@code null} seleciona {@link InjectionStrategy#ADAPTIVE}.
     */
    void setInjectionStrategy(InjectionStrategy strategy);

    /**
     * Define a politica aplicada quando mais de um bean candidato atende ao ponto de injecao.
     *
     * A configuracao programatica tem precedencia sobre a propriedade
     * {@code dependencyContainer.ambiguityPolicy} dos settings.
     *
     * @param policy A politica a ser utilizada. {@code null} seleciona {@link AmbiguityPolicy#FAIL_FAST}.
     */
    void setAmbiguityPolicy(AmbiguityPolicy policy);

    /**
     * Habilita ou desabilita a resolucao de dependencias por tipo generico.
     *
     * Quando desabilitada, o container volta a resolver apenas pela classe crua e pelo qualifier.
     * A configuracao programatica tem precedencia sobre a propriedade
     * {@code dependencyContainer.genericResolution} dos settings.
     */
    void setGenericResolutionEnabled(boolean enabled);

    /**
     * Define o que fazer com um bean prototype marcado com {@code @Event} durante o scan inicial.
     *
     * Um listener prototype nao tem instancia unica: cada resolucao cria outra. O padrao
     * {@link PrototypeListenerPolicy#SKIP} nao registra e emite um aviso.
     * {@link PrototypeListenerPolicy#REGISTER} registra uma instancia dedicada do scan, util para
     * listeners de acao isolada e sem estado compartilhado.
     *
     * A configuracao programatica tem precedencia sobre a propriedade
     * {@code dependencyContainer.prototypeListenerPolicy} dos settings.
     *
     * @param policy A politica a ser utilizada. {@code null} seleciona {@link PrototypeListenerPolicy#SKIP}.
     */
    void setPrototypeListenerPolicy(PrototypeListenerPolicy policy);

}
