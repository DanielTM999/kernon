package dtm.di.common;

import dtm.di.common.reflection.ReflectionCache;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

public final class AnnotationsUtils {

    private AnnotationsUtils(){
        throw new IllegalStateException("utility class");
    }

    /**
     * Retorna todos os campos da classe (incluindo superclasses) que possuem a anotação especificada.
     *
     * @param refClass         Classe de referência.
     * @param annotationClass  Classe da anotação.
     * @param <A>              Tipo da anotação.
     * @return Lista de campos anotados com a anotação fornecida.
     */
    public static  <A extends Annotation> List<Field> getAllFieldWithAnnotation(Class<?> refClass, Class<A> annotationClass){
        Objects.requireNonNull(refClass, "refClass não pode ser null");
        Objects.requireNonNull(annotationClass, "annotationClass não pode ser null");

        return new ArrayList<>(ReflectionCache.fieldsWithAnnotation(refClass, annotationClass));
    }

    /**
     * Retorna todos os campos da classe (incluindo superclasses) que possuem a anotação especificada, utilizando execução paralela.
     * <p>
     * Esse método executa a verificação de anotações de forma assíncrona para cada campo, utilizando o {@link ExecutorService} fornecido.
     * Ele é útil quando há muitas classes ou campos e deseja-se maior desempenho com inspeção paralela.
     * </p>
     *
     * @param refClass         A classe base cuja hierarquia será verificada.
     * @param annotationClass  A classe da anotação a ser procurada.
     * @param executorService  Executor para execução das tarefas assíncronas.
     * @param <A>              Tipo da anotação.
     * @return Lista de {@link Field} que possuem a anotação especificada.
     * @throws NullPointerException     Se qualquer um dos parâmetros for {@code null}.
     * @throws RuntimeException         Se ocorrer um erro durante a execução paralela.
     */
    public static <A extends Annotation> List<Field> getAllFieldWithAnnotation(Class<?> refClass, Class<A> annotationClass, ExecutorService executorService){
        Objects.requireNonNull(refClass, "refClass não pode ser null");
        Objects.requireNonNull(annotationClass, "annotationClass não pode ser null");
        Objects.requireNonNull(executorService, "executorService não pode ser null");

        List<Field> injectableFields = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        while (refClass != null && refClass != Object.class) {
            final Field[] declaredFields = refClass.getDeclaredFields();
            for (final Field field : declaredFields) {
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    if (field.isAnnotationPresent(annotationClass)) {
                        injectableFields.add(field);
                    }
                }, executorService);
                tasks.add(task);
            }
            refClass = refClass.getSuperclass();
        }

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ex) {
            throw new RuntimeException("Erro durante leitura paralela dos campos anotados", ex.getCause());
        }

        return injectableFields;
    }


    /**
     * Verifica se a classe fornecida possui diretamente ou indiretamente (via meta-anotação)
     * uma anotação específica.
     *
     * @param targetClass     Classe que será analisada.
     * @param baseAnnotation  Anotação base que se deseja verificar.
     * @return {@code true} se a classe estiver anotada com a anotação especificada diretamente
     *         ou por meio de meta-anotações; {@code false} caso contrário.
     */
    public static boolean hasMetaAnnotation(Class<?> targetClass, Class<? extends Annotation> baseAnnotation){
        Objects.requireNonNull(targetClass, "targetClass não pode ser null");
        Objects.requireNonNull(baseAnnotation, "baseAnnotation não pode ser null");

        return ReflectionCache.hasMetaAnnotation(targetClass, baseAnnotation);
    }

    /**
     * Verifica se o método fornecido possui diretamente ou indiretamente (via meta-anotação)
     * uma anotação específica.
     *
     * @param targetMethod    Método que será analisado.
     * @param baseAnnotation  Anotação base que se deseja verificar.
     * @return {@code true} se o método estiver anotado com a anotação especificada diretamente
     *         ou por meio de meta-anotações; {@code false} caso contrário.
     */
    public static boolean hasMetaAnnotation(Method targetMethod, Class<? extends Annotation> baseAnnotation){
        Objects.requireNonNull(targetMethod, "targetMethod não pode ser null");
        Objects.requireNonNull(baseAnnotation, "baseAnnotation não pode ser null");

        return ReflectionCache.hasMetaAnnotation(targetMethod, baseAnnotation);
    }


    /**
     * Obtém uma instância de uma anotação presente na classe, seja diretamente ou via meta-anotação.
     *
     * @param targetClass     A classe onde a anotação será buscada.
     * @param baseAnnotation  A classe da anotação desejada.
     * @param <A>             O tipo da anotação.
     * @return A instância da anotação encontrada ou {@code null} se não existir.
     */
    public static <A extends Annotation> A getMetaAnnotation(Class<?> targetClass, Class<A> baseAnnotation){
        Objects.requireNonNull(targetClass, "targetClass não pode ser null");
        Objects.requireNonNull(baseAnnotation, "baseAnnotation não pode ser null");

        return ReflectionCache.metaAnnotation(targetClass, baseAnnotation);
    }

    /**
     * Obtém uma anotação presente diretamente ou como meta-anotação em um método.
     */
    public static <A extends Annotation> A getMetaAnnotation(Method targetMethod, Class<A> baseAnnotation){
        Objects.requireNonNull(targetMethod, "targetMethod não pode ser null");
        Objects.requireNonNull(baseAnnotation, "baseAnnotation não pode ser null");

        return ReflectionCache.metaAnnotation(targetMethod, baseAnnotation);
    }








}
