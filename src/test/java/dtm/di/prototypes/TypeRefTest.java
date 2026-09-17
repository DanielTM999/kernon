package dtm.di.prototypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRefTest {

    interface Processor<T> {}

    interface Foo {}

    interface Bar {}

    @Test
    @DisplayName("1. tipo simples expoe a propria classe")
    void simpleType() {
        TypeRef<String> reference = new TypeRef<>() {};

        assertEquals(String.class, reference.getRawType());
        assertEquals(String.class, reference.getType());
    }

    @Test
    @DisplayName("2. tipo parametrizado preserva o argumento e expoe a classe crua")
    void parameterizedType() {
        TypeRef<Processor<Foo>> reference = new TypeRef<>() {};

        assertEquals(Processor.class, reference.getRawType());
        ParameterizedType type = assertInstanceOf(ParameterizedType.class, reference.getType());
        assertEquals(Foo.class, type.getActualTypeArguments()[0]);
    }

    @Test
    @DisplayName("3. argumento aninhado e preservado")
    void nestedType() {
        TypeRef<Processor<List<Foo>>> reference = new TypeRef<>() {};

        assertEquals(Processor.class, reference.getRawType());
        assertTrue(reference.toString().contains(List.class.getName()));
        assertTrue(reference.toString().contains(Foo.class.getName()));
    }

    @Test
    @DisplayName("4. multiplos argumentos sao preservados")
    void multipleArguments() {
        TypeRef<Map<String, Foo>> reference = new TypeRef<>() {};

        assertEquals(Map.class, reference.getRawType());
        ParameterizedType type = assertInstanceOf(ParameterizedType.class, reference.getType());
        assertEquals(String.class, type.getActualTypeArguments()[0]);
        assertEquals(Foo.class, type.getActualTypeArguments()[1]);
    }

    @Test
    @DisplayName("5. igualdade distingue argumentos de tipo diferentes")
    void equalityDistinguishesTypeArguments() {
        TypeRef<Processor<Foo>> foo = new TypeRef<>() {};
        TypeRef<Processor<Foo>> sameFoo = new TypeRef<>() {};
        TypeRef<Processor<Bar>> bar = new TypeRef<>() {};

        assertEquals(foo, sameFoo);
        assertEquals(foo.hashCode(), sameFoo.hashCode());
        assertNotEquals(foo, bar);
    }

    @Test
    @DisplayName("6. sem argumento de tipo a construcao falha")
    void rawConstructionFails() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TypeRef() {}
        );

        assertTrue(error.getMessage().contains("TypeRef"));
    }
}
