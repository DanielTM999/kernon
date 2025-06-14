package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que o componente ou método pertence a um perfil específico de configuração.
 *
 * Pode ser usado para ativar ou desativar componentes conforme o perfil ativo na aplicação,
 * facilitando a customização e modularização do comportamento.
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
public @interface Profile {
    String value();
}
