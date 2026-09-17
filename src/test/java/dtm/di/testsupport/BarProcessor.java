package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics")
public class BarProcessor implements GenericProcessor<BarPayload> {
    @Override
    public String describe() {
        return "bar-processor";
    }
}
