package dtm.di.common.reflection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericTypesTest {

    interface Processor<T> {
        void process(T value);
    }

    interface Foo {}

    interface Bar {}

    interface SubFoo extends Foo {}

    static class FooProcessor implements Processor<Foo> {
        public void process(Foo value) {}
    }

    static class SubFooProcessor implements Processor<SubFoo> {
        public void process(SubFoo value) {}
    }

    static class IntegerProcessor implements Processor<Integer> {
        public void process(Integer value) {}
    }

    static class BaseProcessor<T> implements Processor<T> {
        public void process(T value) {}
    }

    static class StringProcessor extends BaseProcessor<String> {}

    static class NestedProcessor extends BaseProcessor<List<String>> {}

    static class RawProcessor implements Processor {
        public void process(Object value) {}
    }

    static class OpenProcessor<T> extends BaseProcessor<T> {}

    interface Repository<K, V> {}

    static class UserRepository implements Repository<Long, String> {}

    static class SelfBounded implements Comparable<SelfBounded> {
        public int compareTo(SelfBounded other) {
            return 0;
        }
    }

    static class Holder {
        Processor<Foo> exact;
        Processor<?> unbounded;
        Processor<? extends Number> upperBounded;
        Processor<? super Integer> lowerBounded;
        Processor raw;
        Processor<List<String>> nested;
        Repository<Long, String> multiArgument;
    }

    private static Type declared(String fieldName) throws NoSuchFieldException {
        Field field = Holder.class.getDeclaredField(fieldName);
        return field.getGenericType();
    }

    private static Set<String> keys(Class<?> implementation) {
        return GenericTypes.keysOf(GenericTypes.supertypes(implementation));
    }

    @Test
    @DisplayName("1. implementacao direta expoe a chave generica resolvida")
    void directImplementation() {
        assertTrue(keys(FooProcessor.class).contains(GenericTypesTest.Processor.class.getName() + "<" + Foo.class.getName() + ">"));
    }

    @Test
    @DisplayName("2. herança substitui a variavel de tipo ao longo da cadeia")
    void typeVariableSubstitution() {
        Set<String> keys = keys(StringProcessor.class);

        assertTrue(keys.contains(Processor.class.getName() + "<java.lang.String>"));
        assertTrue(keys.contains(BaseProcessor.class.getName() + "<java.lang.String>"));
    }

    @Test
    @DisplayName("3. substituicao alcanca argumentos aninhados")
    void nestedSubstitution() {
        assertTrue(keys(NestedProcessor.class).contains(Processor.class.getName() + "<java.util.List<java.lang.String>>"));
    }

    @Test
    @DisplayName("4. implementacao crua nao produz chave generica")
    void rawImplementationHasNoGenericKey() {
        assertTrue(keys(RawProcessor.class).stream().noneMatch(key -> key.startsWith(Processor.class.getName() + "<")));
    }

    @Test
    @DisplayName("5. variavel de tipo nao resolvida vira chave incompleta")
    void unresolvedTypeVariable() {
        Set<String> keys = keys(OpenProcessor.class);

        assertTrue(keys.stream().anyMatch(key -> key.contains(GenericTypes.UNRESOLVED)));
        assertTrue(keys.stream().filter(key -> key.startsWith(Processor.class.getName() + "<"))
                .noneMatch(GenericTypes::isFullyResolved));
    }

    @Test
    @DisplayName("6. multiplos argumentos sao renderizados na ordem declarada")
    void multipleArguments() {
        assertTrue(keys(UserRepository.class).contains(Repository.class.getName() + "<java.lang.Long,java.lang.String>"));
    }

    @Test
    @DisplayName("7. hierarquia auto referente nao entra em loop")
    void selfBoundedDoesNotLoop() {
        assertTrue(keys(SelfBounded.class).contains("java.lang.Comparable<" + SelfBounded.class.getName() + ">"));
    }

    @Test
    @DisplayName("8. match exato respeita invariancia")
    void exactMatchIsInvariant() throws Exception {
        Type requested = declared("exact");

        assertTrue(matchesAny(requested, FooProcessor.class));
        assertFalse(matchesAny(requested, SubFooProcessor.class));
        assertFalse(matchesAny(requested, IntegerProcessor.class));
    }

    @Test
    @DisplayName("9. wildcard sem limite aceita qualquer especializacao")
    void unboundedWildcardMatchesAll() throws Exception {
        Type requested = declared("unbounded");

        assertTrue(matchesAny(requested, FooProcessor.class));
        assertTrue(matchesAny(requested, IntegerProcessor.class));
    }

    @Test
    @DisplayName("10. wildcard extends respeita o limite superior")
    void upperBoundedWildcard() throws Exception {
        Type requested = declared("upperBounded");

        assertTrue(matchesAny(requested, IntegerProcessor.class));
        assertFalse(matchesAny(requested, FooProcessor.class));
    }

    @Test
    @DisplayName("11. wildcard super respeita o limite inferior")
    void lowerBoundedWildcard() throws Exception {
        Type requested = declared("lowerBounded");

        assertTrue(matchesAny(requested, IntegerProcessor.class));
        assertFalse(matchesAny(requested, FooProcessor.class));
    }

    @Test
    @DisplayName("12. tipo cru aceita qualquer especializacao")
    void rawRequestMatchesAll() throws Exception {
        Type requested = declared("raw");

        assertTrue(matchesAny(requested, FooProcessor.class));
        assertTrue(matchesAny(requested, IntegerProcessor.class));
    }

    @Test
    @DisplayName("13. argumento aninhado compara de forma invariante")
    void nestedArgumentMatch() throws Exception {
        Type requested = declared("nested");

        assertTrue(matchesAny(requested, NestedProcessor.class));
        assertFalse(matchesAny(requested, StringProcessor.class));
    }

    @Test
    @DisplayName("14. multiplos argumentos casam apenas na combinacao correta")
    void multiArgumentMatch() throws Exception {
        Type requested = declared("multiArgument");

        assertTrue(matchesAny(requested, UserRepository.class));
    }

    @Test
    @DisplayName("15. chave sem interrogacao e considerada resolvida")
    void fullyResolvedDetection() {
        assertTrue(GenericTypes.isFullyResolved("a.b.C<d.E>"));
        assertFalse(GenericTypes.isFullyResolved("a.b.C<?>"));
        assertFalse(GenericTypes.isFullyResolved(null));
    }

    @Test
    @DisplayName("16. raw resolve array generico e variavel de tipo")
    void rawResolution() {
        assertEquals(Processor.class, GenericTypes.raw(GenericTypes.supertypes(FooProcessor.class).stream()
                .filter(type -> GenericTypes.raw(type) == Processor.class)
                .findFirst()
                .orElseThrow()));
    }

    private static boolean matchesAny(Type requested, Class<?> implementation) {
        return GenericTypes.supertypes(implementation).stream()
                .anyMatch(candidate -> GenericTypes.matches(requested, candidate));
    }
}
