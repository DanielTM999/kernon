package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indica que a aplicação de AOP (Programação Orientada a Aspectos) deve ser desabilitada
 * para a classe ou método anotado.
 *
 * <p>Se aplicado em uma classe comum, desabilita a interceptação e os proxies AOP
 * apenas para aquela classe.</p>
 *
 * <p>Se aplicado na classe principal de inicialização da aplicação (classe de boot),
 * desabilita completamente o suporte a AOP em toda a aplicação.</p>
 *
 * <p>Quando aplicado em métodos, vale somente para métodos que definem beans,
 * desabilitando AOP apenas para o bean criado por aquele método.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface DisableAop {
}
