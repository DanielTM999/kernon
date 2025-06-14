package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma classe ou método como um serviço gerenciado pelo contêiner de dependências.
 *
 * Esta annotation é uma especialização de {@link Component} e pode ser usada para indicar
 * que o componente representa um serviço da camada de negócio.
 *
 * Também suporta qualificadores para distinguir múltiplas implementações do mesmo serviço.
 */
@Component
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Service {
    String qualifier() default "default";
}

