package dtm.di.common.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Cache central de metadados de reflection (Field[], Constructor[], Method[]) por classe.
 *
 * O JDK retorna uma cópia nova dos arrays a cada chamada de getDeclaredFields/Methods/Constructors.
 * Em apps com muitos beans, esse custo aparece no boot. Aqui guardamos UMA referência por classe
 * e reusamos. Os arrays expostos são imutáveis — listas {@link Collections#unmodifiableList} —
 * para impedir que callers mutem o estado compartilhado.
 *
 * Pensado para ser thread-safe (ConcurrentHashMap.computeIfAbsent) e usável em parallelStream.
 */
public final class ReflectionCache {

    private ReflectionCache() {
        throw new IllegalStateException("utility class");
    }

    private static final ConcurrentMap<Class<?>, ClassMetadata> CACHE = new ConcurrentHashMap<>();

    public static List<Field> fields(Class<?> clazz) {
        return metadata(clazz).fields;
    }

    public static List<Constructor<?>> constructors(Class<?> clazz) {
        return metadata(clazz).constructors;
    }

    public static List<Method> methods(Class<?> clazz) {
        return metadata(clazz).methods;
    }

    /**
     * Campos da classe e de toda a hierarquia (parando em Object).
     */
    public static List<Field> fieldsWithHierarchy(Class<?> clazz) {
        return metadata(clazz).fieldsHierarchy.get();
    }

    /**
     * Métodos da classe e de toda a hierarquia (parando em Object/null).
     */
    public static List<Method> methodsWithHierarchy(Class<?> clazz) {
        return metadata(clazz).methodsHierarchy.get();
    }

    /**
     * Lista de campos com a anotação especificada, considerando hierarquia.
     * Computado uma vez por (classe, anotação) e cacheado.
     */
    public static <A extends Annotation> List<Field> fieldsWithAnnotation(Class<?> clazz, Class<A> annotation) {
        return metadata(clazz).fieldsByAnnotation.computeIfAbsent(annotation, ann -> {
            List<Field> all = fieldsWithHierarchy(clazz);
            List<Field> filtered = new ArrayList<>();
            for (Field field : all) {
                if (field.isAnnotationPresent(ann)) {
                    filtered.add(field);
                }
            }
            return Collections.unmodifiableList(filtered);
        });
    }

    /**
     * Lista de métodos com a anotação especificada, considerando hierarquia.
     * Computado uma vez por (classe, anotação) e cacheado.
     */
    public static <A extends Annotation> List<Method> methodsWithAnnotation(Class<?> clazz, Class<A> annotation) {
        return metadata(clazz).methodsByAnnotation.computeIfAbsent(annotation, ann -> {
            List<Method> all = methodsWithHierarchy(clazz);
            List<Method> filtered = new ArrayList<>();
            for (Method method : all) {
                if (method.isAnnotationPresent(ann)) {
                    filtered.add(method);
                }
            }
            return Collections.unmodifiableList(filtered);
        });
    }

    /**
     * Limpa todo o cache. Útil em testes ou hot-reload.
     */
    public static void clear() {
        CACHE.clear();
    }

    public static void clear(Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        CACHE.remove(clazz);
    }

    public static void clear(Collection<Class<?>> classes) {
        if (classes == null) {
            return;
        }
        for (Class<?> clazz : classes) {
            clear(clazz);
        }
    }

    private static ClassMetadata metadata(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, ClassMetadata::new);
    }

    private static final class ClassMetadata {
        final List<Field> fields;
        final List<Constructor<?>> constructors;
        final List<Method> methods;
        final Lazy<List<Field>> fieldsHierarchy;
        final Lazy<List<Method>> methodsHierarchy;
        final ConcurrentMap<Class<? extends Annotation>, List<Field>> fieldsByAnnotation = new ConcurrentHashMap<>();
        final ConcurrentMap<Class<? extends Annotation>, List<Method>> methodsByAnnotation = new ConcurrentHashMap<>();

        ClassMetadata(Class<?> clazz) {
            this.fields = Collections.unmodifiableList(Arrays.asList(clazz.getDeclaredFields()));
            this.constructors = Collections.unmodifiableList(Arrays.asList(clazz.getDeclaredConstructors()));
            this.methods = Collections.unmodifiableList(Arrays.asList(clazz.getDeclaredMethods()));
            this.fieldsHierarchy = new Lazy<>(() -> walkHierarchy(clazz, ReflectionCache::fields));
            this.methodsHierarchy = new Lazy<>(() -> walkHierarchy(clazz, ReflectionCache::methods));
        }
    }

    private static <T> List<T> walkHierarchy(Class<?> clazz, Function<Class<?>, List<T>> extractor) {
        List<T> all = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            all.addAll(extractor.apply(current));
            current = current.getSuperclass();
        }
        return Collections.unmodifiableList(all);
    }

    private static final class Lazy<T> {
        private final java.util.function.Supplier<T> supplier;
        private volatile T value;

        Lazy(java.util.function.Supplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() {
            T v = value;
            if (v == null) {
                synchronized (this) {
                    v = value;
                    if (v == null) {
                        v = supplier.get();
                        value = v;
                    }
                }
            }
            return v;
        }
    }
}
