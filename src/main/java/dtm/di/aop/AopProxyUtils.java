package dtm.di.aop;

import dtm.di.annotations.aop.*;
import dtm.di.core.DependencyContainer;
import dtm.di.core.aop.AopUtils;
import dtm.di.exceptions.AspectNewInstanceException;

import java.lang.reflect.Parameter;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 🚫 API INTERNA DO FRAMEWORK 🚫
 * <p>
 * Implementação do serviço interno de AOP (Aspect-Oriented Programming) do framework.
 * <p>
 * Essa classe não faz parte da API pública e não deve ser utilizada diretamente pelos usuários do framework.
 * Seu objetivo é fornecer a implementação de como o mecanismo de AOP funciona por trás dos panos dentro do
 * container de injeção de dependências.
 * <p>
 * Essa classe é responsável por gerenciar, executar e controlar os aspectos declarados com as anotações:
 * {@link dtm.di.annotations.aop.Aspect}, {@link dtm.di.annotations.aop.Pointcut},
 * {@link dtm.di.annotations.aop.BeforeExecution} e {@link dtm.di.annotations.aop.AfterExecution}.
 * <p>
 *  Isso existe única e exclusivamente para ser o serviço interno de AOP do framework, garantindo que a
 * interceptação e execução dos aspectos aconteça corretamente.
 * <p>
 * O usuário final não interage com essa classe e, em teoria, nem precisa saber que ela existe.
 */
public class AopProxyUtils extends AopUtils {
    private static volatile AopProxyUtils aopProxyUtils;

    private final DependencyContainer dependencyContainer;
    private final Set<AspectHandler> handlers;

    private AopProxyUtils(DependencyContainer dependencyContainer){
        this.dependencyContainer = dependencyContainer;
        this.handlers = ConcurrentHashMap.newKeySet();
        createAspects();
    }

    /**
     * Executa todos os métodos anotados com {@link BeforeExecution} para os aspectos registrados,
     * desde que o {@link Pointcut} (se existir) retorne {@code true}.
     *
     * @param method Método alvo que será executado.
     * @param args Argumentos que serão passados para o método alvo.
     * @param proxy Instância proxy (ou objeto real) no qual o método será executado.
     */
    @Override
    public void applyBefore(Method method, Object[] args, Object proxy, Object realInstance) {
        for (AspectHandler handler : handlers) {
            boolean apply = shouldApplyHandler(handler, method, args, proxy, realInstance);

            if(!apply) continue;

            Method before = handler.before;
            if(before != null){
                try {
                    executeMethod(
                            handler.instance,
                            before,
                            method,
                            args,
                            proxy,
                            realInstance
                    );
                }catch (RuntimeException re){
                    throw re;
                } catch (Exception e) {
                    String className = (handler.instance != null) ?  handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @BeforeExecution: "+className, e);
                }
            }
        }

    }

    /**
     * Executa todos os métodos anotados com {@link AfterExecution} para os aspectos registrados,
     * desde que o {@link Pointcut} (se existir) retorne {@code true}.
     * <p>
     * Se o método {@code @AfterExecution} retornar um valor não nulo e compatível com o tipo de retorno
     * do método interceptado, esse valor substituirá o resultado original.
     *
     * @param method Método alvo que foi executado.
     * @param args Argumentos que foram passados para o método alvo.
     * @param proxy Instância proxy (ou objeto real) no qual o método foi executado.
     * @param currentResult Resultado atual da execução do método alvo.
     * @return O resultado final, podendo ser o original ou um valor alterado pelo aspecto {@code @AfterExecution}.
     */
    @Override
    public Object applyAfter(Method method, Object[] args, Object proxy, Object realInstance, Object currentResult) {
        Object result = currentResult;

        for (AspectHandler handler : handlers) {
            boolean apply = shouldApplyHandler(handler, method, args, proxy, realInstance);

            if(!apply) continue;

            Method after = handler.after;
            if (after != null) {
                try {
                    after.setAccessible(true);
                    Object newResult = executeMethod(
                            handler.instance,
                            after,
                            method,
                            args,
                            proxy,
                            realInstance,
                            result
                    );
                    if (newResult != null && method.getReturnType().isAssignableFrom(newResult.getClass())) {
                        result = newResult;
                    }
                } catch (Exception e) {

                    String className = (handler.instance != null) ? handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @AfterExecution: "+className, e);
                }
            }
        }

        return result;
    }


    @Override
    public void applyOnErrorMethod(Method method, Object[] args, Object proxy, Object realInstance, Throwable cause) {
        for (AspectHandler handler : handlers) {
            boolean apply = shouldApplyHandler(handler, method, args, proxy, realInstance);

            if(!apply) continue;
            Method error = handler.error;
            if(error != null){
                try {
                    executeMethod(
                            handler.instance,
                            error,
                            method,
                            args,
                            proxy,
                            realInstance,
                            null,
                            cause
                    );
                }catch (RuntimeException re){
                    throw re;
                } catch (Exception e) {
                    String className = (handler.instance != null) ?  handler.instance.getClass().toString() : method.getName();
                    throw new RuntimeException("Erro no método @AfterExecution: "+className, e);
                }
            }
        }
    }

    /**
     * Responsável por escanear todas as classes registradas no container que estão anotadas com {@link Aspect}.
     * <p>
     * Para cada classe encontrada, registra os métodos anotados com {@link Pointcut}, {@link BeforeExecution} e
     * {@link AfterExecution}, armazenando-os na coleção interna {@code handlers}.
     */
    private void createAspects(){
        Set<Class<?>> aspects = dependencyContainer
                .getLoadedSystemClasses()
                .parallelStream()
                .filter(c -> c.isAnnotationPresent(Aspect.class))
                .collect(Collectors.toSet());

        for (Class<?> clazz : aspects){
            try{
                Object instance = dependencyContainer.newInstance(clazz);
                Method pointcut = null, before = null, after = null, error = null;
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Pointcut.class))
                        pointcut = method;
                    else if (method.isAnnotationPresent(BeforeExecution.class))
                        before = method;
                    else if (method.isAnnotationPresent(AfterExecution.class))
                        after = method;
                    else if(method.isAnnotationPresent(AfterException.class))
                        error = method;
                }

                handlers.add(new AspectHandler(instance, pointcut, before, after, error));
            }catch (Exception e){
                throw new AspectNewInstanceException(e.getMessage(), clazz, e);
            }
        }
    }

    /**
     * Avalia se o aspecto deve ser aplicado a um determinado método, com base na execução do método {@code @Pointcut}.
     *
     * @param handler Handler do aspecto contendo referências para os métodos {@code pointcut}, {@code before} e {@code after}.
     * @param method Método alvo.
     * @param args Argumentos do método alvo.
     * @param proxy Instância proxy (ou objeto real).
     * @return {@code true} se deve aplicar o aspecto, {@code false} caso contrário.
     */
    private boolean shouldApplyHandler(AspectHandler handler, Method method, Object[] args, Object proxy, Object realInstance) {
        if (handler.pointcut == null) return true;
        try {
            Object result = executeMethod(handler.instance, handler.pointcut, method, args, proxy, realInstance);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * Executa um método do aspecto, resolvendo automaticamente os parâmetros anotados com:
     * <ul>
     *     <li>{@link ProxyInstance} → injeta o proxy da classe alvo</li>
     *     <li>{@link ResultProxy} → injeta o resultado atual da execução do método alvo (somente em {@code after})</li>
     *     <li>{@code Method} → injeta a referência do método alvo interceptado</li>
     *     <li>{@code Object[]} → injeta os argumentos do método interceptado</li>
     * </ul>
     *
     * @param instance Instância da classe do aspecto.
     * @param methodExecute Método a ser executado.
     * @param methodArgs Referência do método alvo interceptado.
     * @param args Argumentos do método alvo interceptado.
     * @param proxy Proxy da instância alvo.
     * @return Resultado da execução do método do aspecto.
     * @throws Exception Caso ocorra algum erro na invocação do método.
     */
    private Object executeMethod(
            Object instance,
            Method methodExecute,
            Method methodArgs,
            Object[] args,
            Object proxy,
            Object realInstance
    ) throws Exception{
        return executeMethod(instance, methodExecute, methodArgs, args, proxy, realInstance, null, null);
    }

    /**
     * Executa um método do aspecto, resolvendo automaticamente os parâmetros anotados com:
     * <ul>
     *     <li>{@link ProxyInstance} → injeta o proxy da classe alvo</li>
     *     <li>{@link ResultProxy} → injeta o resultado atual da execução do método alvo (somente em {@code after})</li>
     *     <li>{@code Method} → injeta a referência do método alvo interceptado</li>
     *     <li>{@code Object[]} → injeta os argumentos do método interceptado</li>
     * </ul>
     *
     * @param instance Instância da classe do aspecto.
     * @param methodExecute Método a ser executado.
     * @param methodArgs Referência do método alvo interceptado.
     * @param args Argumentos do método alvo interceptado.
     * @param proxy Proxy da instância alvo.
     * @param currentResult Resultado atual do método alvo (somente em {@code after}).
     * @return Resultado da execução do método do aspecto.
     * @throws Exception Caso ocorra algum erro na invocação do método.
     */
    private Object executeMethod(
            Object instance,
            Method methodExecute,
            Method methodArgs,
            Object[] args,
            Object proxy,
            Object realInstance,
            Object currentResult
    ) throws Exception {
        return executeMethod(instance, methodExecute, methodArgs, args, proxy, realInstance, currentResult, null);
    }

    /**
     * Executa um método do aspecto, resolvendo automaticamente os parâmetros anotados com:
     * <ul>
     *     <li>{@link ProxyInstance} → injeta o proxy da classe alvo</li>
     *     <li>{@link ResultProxy} → injeta o resultado atual da execução do método alvo (somente em {@code after})</li>
     *     <li>{@code Method} → injeta a referência do método alvo interceptado</li>
     *     <li>{@code Object[]} → injeta os argumentos do método interceptado</li>
     *     <li>{@code Throwable} → injeta o erro do método interceptado</li>
     * </ul>
     *
     * @param instance Instância da classe do aspecto.
     * @param methodExecute Método a ser executado.
     * @param methodArgs Referência do método alvo interceptado.
     * @param args Argumentos do método alvo interceptado.
     * @param proxy Proxy da instância alvo.
     * @param currentResult Resultado atual do método alvo (somente em {@code after}).
     * @param cause Exception lancada pelo metodo.
     * @return Resultado da execução do método do aspecto.
     * @throws Exception Caso ocorra algum erro na invocação do método.
     */
    private Object executeMethod(
            Object instance,
            Method methodExecute,
            Method methodArgs,
            Object[] args,
            Object proxy,
            Object realInstance,
            Object currentResult,
            Throwable cause
    ) throws Exception{
        methodExecute.setAccessible(true);
        Parameter[] parameters = methodExecute.getParameters();
        Object[] invokeArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            Class<?> paramType = parameter.getType();

            if (parameter.isAnnotationPresent(ProxyInstance.class)) {
                invokeArgs[i] = proxy;
            } else if (parameter.isAnnotationPresent(ResultProxy.class)) {
                invokeArgs[i] = currentResult;
            } else if (parameter.isAnnotationPresent(ReferenceIntance.class)) {
                invokeArgs[i] = realInstance;
            } else if (Method.class.isAssignableFrom(paramType)) {
                invokeArgs[i] = methodArgs;
            } else if (paramType.isArray() && paramType.getComponentType().equals(Object.class)) {
                invokeArgs[i] = args;
            }else if(Throwable.class.isAssignableFrom(paramType)){
                invokeArgs[i] = cause;
            }else {
                invokeArgs[i] = null;
            }
        }

        return methodExecute.invoke(instance, invokeArgs);
    }

    /**
     * Representa um handler de aspecto contendo:
     * <ul>
     *     <li>Instância do aspecto</li>
     *     <li>Método {@code @Pointcut} (opcional)</li>
     *     <li>Método {@code @BeforeExecution} (opcional)</li>
     *     <li>Método {@code @AfterExecution} (opcional)</li>
     * </ul>
     *
     * @param instance Instância da classe anotada com {@link Aspect}.
     * @param pointcut Método anotado com {@link Pointcut}.
     * @param before Método anotado com {@link BeforeExecution}.
     * @param after Método anotado com {@link AfterExecution}.
     */
    public record AspectHandler(Object instance, Method pointcut, Method before, Method after, Method error) {
    }

    /**
     * Singleton para obter a instância de {@link AopProxyUtils} associada ao {@link DependencyContainer}.
     *
     * @param dependencyContainer Container de injeção de dependências onde os aspectos estão registrados.
     * @return Instância singleton de {@link AopProxyUtils}.
     */
    public static AopUtils getInstance(DependencyContainer dependencyContainer) {
        if (aopProxyUtils == null) {
            synchronized (AopProxyUtils.class) {
                if (aopProxyUtils == null) {
                    aopProxyUtils = new AopProxyUtils(dependencyContainer);
                }
            }
        }
        return aopProxyUtils;
    }

}
