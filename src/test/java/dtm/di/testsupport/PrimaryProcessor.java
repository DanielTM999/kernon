package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Primary;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Primary
@Profile("generics-order")
public class PrimaryProcessor implements GenericProcessor<BarPayload> {
    @Override
    public String describe() {
        return "primary-processor";
    }
}
