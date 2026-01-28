package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Marca um método para atuar como um tratador de exceções específico.
 * * <p>Métodos anotados com {@code @HandleException} devem ser declarados dentro de classes
 * anotadas com {@link ControllerAdvice}. Quando uma exceção do tipo especificado (ou suas
 * subclasses) ocorre durante o ciclo de execução monitorado, o contêiner de injeção
 * redireciona o fluxo para este método.</p>
 *
 * <p>Exemplo de uso:</p>
 * <pre>
 * &#64;HandleException(DatabaseException.class)
 * public void handleDatabaseError(DatabaseException e) {
 * // Lógica de tratamento
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HandleException {
    Class<? extends Throwable>[] value();
}