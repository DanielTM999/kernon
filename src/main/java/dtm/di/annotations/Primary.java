package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um bean como o candidato preferencial quando há múltiplas implementações
 * do mesmo tipo registradas no container.
 *
 * <p>Quando uma dependência é injetada sem {@link Qualifier} explícito (qualificador "default")
 * e há mais de um candidato disponível, o bean anotado com {@code @Primary} é selecionado.
 * Se nenhum estiver marcado como primary, o resolutor mantém o comportamento atual
 * (bean registrado sob o qualificador "default", ou erro se não houver).</p>
 *
 * <p>Pode ser aplicada em classes anotadas com {@link Component}/{@link Service} ou em
 * métodos de fábrica em uma classe {@link Configuration} (anotados com {@link BeanDefinition}).</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Primary {
}
