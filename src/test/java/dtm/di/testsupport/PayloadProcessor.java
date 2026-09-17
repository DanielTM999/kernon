package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Profile("generics-wildcard")
public class PayloadProcessor implements GenericProcessor<FooPayload> {
    @Override
    public String describe() {
        return "payload-processor";
    }
}
