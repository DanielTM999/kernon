package dtm.di.annotations.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Intercepta a execucao principal de um metodo proxificado.
 *
 * <p>Quando ao menos um aspecto aplicavel declara este advice, o container nao
 * chama o metodo original automaticamente. O advice pode receber um
 * {@link java.util.concurrent.Callable} e chamar {@code call()} quando desejar
 * executar o metodo original; tambem pode retornar um resultado sem chama-lo.</p>
 *
 * <p>Os mesmos parametros dos demais advices sao suportados, incluindo
 * {@link java.lang.reflect.Method}, {@code Object[]}, {@link ProxyInstance} e
 * {@link ReferenceInstance}. O parametro {@code Callable<?>} e opcional.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnMainMethod {
}
