package dtm.di.prototypes;

import dtm.di.common.reflection.GenericTypes;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

@SuppressWarnings("unchecked")
public abstract class TypeRef<T> {

    private final Type type;
    private final Class<T> rawType;

    protected TypeRef() {
        Type superType = getClass().getGenericSuperclass();

        if (!(superType instanceof ParameterizedType parameterized)) {
            throw new IllegalArgumentException(
                    "TypeRef exige o argumento de tipo: new TypeRef<Processor<Foo>>(){}"
            );
        }

        this.type = parameterized.getActualTypeArguments()[0];
        Class<?> resolvedRawType = GenericTypes.raw(this.type);

        if (resolvedRawType == null) {
            throw new IllegalArgumentException(
                    "Nao foi possivel resolver a classe crua de " + this.type.getTypeName()
            );
        }

        this.rawType = (Class<T>) resolvedRawType;
    }

    public Type getType() {
        return type;
    }

    public Class<T> getRawType() {
        return rawType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TypeRef<?> that)) return false;
        return Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(type);
    }

    @Override
    public String toString() {
        return GenericTypes.key(type);
    }
}
