package dtm.di.core.aop;

import java.lang.reflect.Method;

/**
 * Infraestrutura interna do mecanismo AOP do framework.
 * <p>
 * Classe abstrata que define o contrato base para o funcionamento do serviço de interceptação AOP.
 * <p>
 * Essa classe não é destinada para uso externo e não faz parte da API pública do framework.
 * Seu papel é fornecer o contrato que a engine AOP utiliza internamente para controlar a execução
 * de aspectos antes e depois da execução dos métodos interceptados.
 * <p>
 * Implementações dessa classe lidam diretamente com o ciclo de vida dos aspectos, resolvendo
 * chamadas aos métodos {@code Pointcut}, {@code BeforeExecution} e {@code AfterExecution}.
 * <p>
 * ✔️ Essa classe existe para desacoplar a definição de interceptadores da implementação do mecanismo AOP.
 */
public abstract class AopUtils {
    public abstract void applyBefore(Method method, Object[] args, Object proxy, Object realIntance);
    public abstract Object applyAfter(Method method, Object[] args, Object proxy, Object realIntance, Object currentResult);
}
