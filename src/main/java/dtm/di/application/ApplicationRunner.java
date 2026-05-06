package dtm.di.application;

/**
 * Beans que implementam esta interface são executados automaticamente após
 * o método {@code @OnBoot} ter rodado, recebendo os argumentos da linha de comando.
 *
 * <p>Útil para tarefas de inicialização que dependem do container já carregado e que
 * precisam acessar os argumentos passados ao programa — sem precisar declará-las como
 * método estático no bootable.</p>
 *
 * <p>São candidatos típicos: importar dados, executar migrações, configurar índices,
 * disparar jobs únicos.</p>
 *
 * <h3>Ordem de execução</h3>
 * <p>Caso múltiplos runners estejam registrados, executam na ordem em que o container
 * os entrega via {@code getInstancesByClass}. Se ordem determinística for necessária,
 * use lógica explícita dentro do próprio runner (ex.: dispatch para outros componentes).</p>
 *
 * <h3>Exceções</h3>
 * <p>Exceções lançadas por um runner propagam para o handler de erro de boot
 * (mesmo caminho de {@code @OnApplicationFail}).</p>
 */
@FunctionalInterface
public interface ApplicationRunner {
    void run(String[] args) throws Exception;
}
