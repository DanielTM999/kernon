package dtm.di.prototypes;

import java.util.Iterator;
import java.util.List;

public interface CompositeDependency<T> extends Iterator<T> {
    List<T> getAsList();
}
