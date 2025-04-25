package dtm.di.storage.lazy;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LazyObject {
    private Class<?> clazz;
    private boolean lazy;
}
