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
 * Se nenhum estiver marcado como primary e o empate persistir, vale a política
 * {@code dependencyContainer.ambiguityPolicy}, cujo padrão lança
 * {@code AmbiguousDependencyException}.</p>
 *
 * <p>{@code @Primary} só é consultado quando nenhum qualifier foi especificado. Um
 * {@link Qualifier} explícito, ou o atributo {@code qualifier} de {@link Component}/
 * {@link Service}, tem precedência e anula a marcação primary.</p>
 *
 * <p>Pode ser aplicada em classes anotadas com {@link Component}/{@link Service} ou em métodos
 * produtores de uma classe {@link Configuration}. Em método produtor, a anotação produtora
 * ({@link Component} ou {@link Service}) também precisa estar presente — {@link BeanDefinition}
 * sozinho não torna o método um produtor.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Primary {
}
