package dtm.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca uma classe como um componente responsável por fornecer
 * conselhos globais (advices) para controladores da aplicação.
 *
 * Classes anotadas com {@code @ControllerAdvice} podem conter
 * lógica transversal como tratamento centralizado de exceções,
 * interceptação de chamadas de controladores ou configuração
 * compartilhada entre múltiplos pontos da camada de controle.
 *
 * O contêiner de injeção detecta automaticamente as classes
 * anotadas e as registra para que suas regras sejam aplicadas
 * de forma global.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ControllerAdvice { }
