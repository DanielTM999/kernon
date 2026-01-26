package dtm.di.common;

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

        List<Field> injectableFields = new ArrayList<>();

        while (refClass != null && refClass != Object.class) {
            for (Field field : refClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(annotationClass)) {
                    injectableFields.add(field);
                }
            }
            refClass = refClass.getSuperclass();
        }

        return injectableFields;
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
        if(targetClass.isAnnotationPresent(baseAnnotation)){
            return true;
        }
        Set<Class<? extends Annotation>> visiting = new HashSet<>();
        for (Annotation annotation : targetClass.getAnnotations()) {
            if (hasMetaAnnotation(annotation.annotationType(), baseAnnotation, visiting)) {
                return true;
            }
        }
        return false;
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
        if(targetMethod.isAnnotationPresent(baseAnnotation)){
            return true;
        }
        Set<Class<? extends Annotation>> visiting = new HashSet<>();
        for (Annotation annotation : targetMethod.getAnnotations()) {
            if (hasMetaAnnotation(annotation.annotationType(), baseAnnotation, visiting)) {
                return true;
            }
        }
        return false;
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

        if(targetClass.isAnnotationPresent(baseAnnotation)){
            return targetClass.getAnnotation(baseAnnotation);
        }
        Set<Class<? extends Annotation>> visiting = new HashSet<>();
        for (Annotation annotation : targetClass.getAnnotations()) {
            A found = findMetaAnnotation(annotation.annotationType(), baseAnnotation, visiting);
            if (found != null) {
                return found;
            }
        }
        return null;
    }





    /**
     * Método auxiliar recursivo que verifica se uma anotação é uma meta-anotação da anotação base desejada.
     * Utiliza um conjunto para rastrear anotações já visitadas e evitar loops infinitos.
     *
     * @param annotationType  Tipo da anotação a ser verificada.
     * @param baseAnnotation  Anotação base alvo.
     * @param visiting        Conjunto de anotações já visitadas (para evitar ciclos).
     * @return {@code true} se a anotação atual ou qualquer uma de suas meta-anotações corresponder à base;
     *         {@code false} caso contrário.
     */
    private static boolean hasMetaAnnotation(Class<? extends Annotation> annotationType, Class<? extends Annotation> baseAnnotation, Set<Class<? extends Annotation>> visiting){
        if (annotationType.equals(baseAnnotation)) {
            return true;
        }

        if (!visiting.add(annotationType)) {
            return false;
        }

        for (Annotation metaAnnotation : annotationType.getAnnotations()) {
            if (hasMetaAnnotation(metaAnnotation.annotationType(), baseAnnotation, visiting)) {
                visiting.remove(annotationType);
                return true;
            }
        }
        visiting.remove(annotationType);

        return false;
    }


    /**
     * Método auxiliar recursivo para encontrar a instância da anotação dentro de uma hierarquia de anotações.
     *
     * @param currentAnnotationType O tipo da anotação atual sendo inspecionada.
     * @param baseAnnotation        A anotação alvo que queremos encontrar.
     * @param visited               Conjunto de anotações já visitadas para evitar ciclos infinitos.
     * @return A instância da anotação se encontrada, ou null.
     */
    private static <A extends Annotation> A findMetaAnnotation(Class<? extends Annotation> currentAnnotationType, Class<A> baseAnnotation, Set<Class<? extends Annotation>> visited) {
        if (!visited.add(currentAnnotationType)) {
            return null;
        }

        if (currentAnnotationType.getName().startsWith("java.lang.annotation")) {
            return null;
        }

        if (currentAnnotationType.isAnnotationPresent(baseAnnotation)) {
            return currentAnnotationType.getAnnotation(baseAnnotation);
        }

        for (Annotation metaAnnotation : currentAnnotationType.getAnnotations()) {
            A found = findMetaAnnotation(metaAnnotation.annotationType(), baseAnnotation, visited);
            if (found != null) {
                return found;
            }
        }

        return null;
    }



}
