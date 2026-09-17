package dtm.di.testsupport;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.Profile;
import dtm.di.annotations.Singleton;

@Singleton
@Component
@Async
@Profile({"generics-async", "generics-async-class"})
public class AsyncStringProcessor implements GenericProcessor<String> {
    @Override
    public String describe() {
        return "async-string-processor";
    }
}
