package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics-wildcard")
public class DoubleProcessor implements GenericProcessor<Double> {
    @Override
    public String describe() {
        return "double-processor";
    }
}
