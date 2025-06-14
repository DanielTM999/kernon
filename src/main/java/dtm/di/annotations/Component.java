package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma classe ou método como um componente gerenciado pelo contêiner de dependências.
 *
 * Pode ser utilizado para indicar que a classe ou o método devem ser registrados
 * como beans/serviços no contêiner.
 *
 * A annotation suporta um qualificadora para distinguir múltiplas implementações
 * do mesmo tipo.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Component {
    String qualifier() default "default";
}
