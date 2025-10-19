package dtm.di.storage.lazy;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ParamtrizedObject {
    private Class<?> baseClass;
    private Class<?> paramClass;
    private boolean isParametrized;
}
