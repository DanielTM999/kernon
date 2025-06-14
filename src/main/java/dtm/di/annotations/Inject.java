package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que um campo ou parâmetro deve ter uma dependência injetada pelo contêiner de dependências.
 *
 * Pode ser utilizado para marcar campos de instância ou parâmetros de construtor/método que
 * serão automaticamente preenchidos pelo mecanismo de injeção de dependência.
 *
 * A anotação suporta o uso de um qualificadora para diferenciar múltiplas implementações
 * da mesma interface ou classe.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Inject {
    /**
     * Qualificador para distinguir múltiplas implementações da dependência.
     * O padrão é "default".
     *
     * @return o nome do qualificador
     */
    String qualifier() default "default";
}
