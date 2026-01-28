package dtm.di.storage.lazy;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.reflect.Type;

@Data
@AllArgsConstructor
public class ParamtrizedObject {
    private Class<?> baseClass;
    private Type paramType;
    private boolean isParametrized;
}
