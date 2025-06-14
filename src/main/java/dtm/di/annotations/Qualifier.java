package dtm.di.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Define um qualificadora para uma dependência, para diferenciar múltiplas implementações
 * da mesma interface ou classe no contêiner de dependências.
 *
 * Pode ser usada para marcar classes, métodos, campos ou parâmetros, indicando qual instância
 * específica deve ser injetada quando houver múltiplas opções disponíveis.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Qualifier {
    String qualifier() default "default";
}
