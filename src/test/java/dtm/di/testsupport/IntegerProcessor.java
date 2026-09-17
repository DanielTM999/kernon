package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile({"generics-wildcard", "generics-order"})
public class IntegerProcessor implements GenericProcessor<Integer> {
    @Override
    public String describe() {
        return "integer-processor";
    }
}
