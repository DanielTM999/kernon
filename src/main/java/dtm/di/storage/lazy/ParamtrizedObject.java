package dtm.di.storage.lazy;

import lombok.Data;

import java.lang.reflect.Type;

@Data
public class ParamtrizedObject {
    private Class<?> baseClass;
    private Type paramType;
    private boolean isParametrized;
    private Type declaredType;

    public ParamtrizedObject(Class<?> baseClass, Type paramType, boolean isParametrized) {
        this(baseClass, paramType, isParametrized, paramType);
    }

    public ParamtrizedObject(Class<?> baseClass, Type paramType, boolean isParametrized, Type declaredType) {
        this.baseClass = baseClass;
        this.paramType = paramType;
        this.isParametrized = isParametrized;
        this.declaredType = declaredType;
    }
}
