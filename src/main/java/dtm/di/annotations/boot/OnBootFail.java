package dtm.di.annotations.boot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o método anotado deve ser executado quando ocorrer uma falha durante
 * o processo de boot da aplicação.
 *
 * <p>O método anotado deve ser estático, ter retorno void e pode receber um parâmetro
 * do tipo {@link Throwable} que representa a exceção lançada durante o boot.</p>
 *
 * <h3>Assinatura esperada do método:</h3>
 * <pre>{@code
 * public static void metodoOnBootFail(Throwable throwable) {
 *     // tratamento da exceção
 * }
 * }</pre>
 *
 * <h3>Exemplo de uso:</h3>
 * <pre>{@code
 * public class AppErrorHandler {
 *
 *     @OnBootFail
 *     public static void handleBootFailure(Throwable error) {
 *         System.err.println("Falha no boot: " + error.getMessage());
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnBootFail {
}
