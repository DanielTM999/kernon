package dtm.di.annotations.aop;

import java.lang.annotation.*;

/**
 * Indica que o método anotado deve ser executado <b>antes</b> da execução
 * de um método alvo, como parte de um Aspecto (AOP).
 *
 * <p>Este método é executado antes do método interceptado. Seu retorno,
 * se houver, é <b>ignorado</b> diretamente, mas o método pode influenciar
 * o comportamento do método interceptado de forma indireta, alterando:</p>
 * <ul>
 *     <li>Os argumentos ({@code Object[] args}), que são mutáveis.</li>
 *     <li>O estado do proxy ({@code Object proxy}), se utilizado.</li>
 *     <li>O próprio {@link java.lang.reflect.Method} por meio de reflexão, como alterar
 *     acessibilidade ou inspecionar metadados.</li>
 * </ul>
 *
 * <h3>Parâmetros suportados:</h3>
 * <ul>
 *     <li>{@link java.lang.reflect.Method} → Método alvo que será executado.</li>
 *     <li>{@code Object[]} → Argumentos do método interceptado.</li>
 *     <li>{@code Object proxy} → Instância proxy (se anotado com {@link dtm.di.annotations.aop.ProxyInstance}).</li>
 * </ul>
 * <p>Consulte {@link dtm.di.annotations.aop.ProxyInstance} para entender como acessar o proxy da instância atual.</p>
 * <p>Consulte {@link dtm.di.annotations.aop.ResultProxy} para acessar ou modificar o valor retornado pelo método interceptado.</p>
 *
 * <p>O método anotado <b>não recebe</b> o resultado da execução, pois ele ocorre
 * <i>antes</i> do método alvo ser chamado.</p>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * @Aspect
 * public class LoggerAspect {
 *
 *     @BeforeExecution
 *     public void logBefore(Method method, Object[] args) {
 *         System.out.println("Chamando: " + method.getName());
 *         args[0] = "Valor alterado"; // Altera o primeiro argumento do método alvo
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BeforeExecution {
}