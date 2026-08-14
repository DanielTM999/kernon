package dtm.di.testsupport;

import dtm.di.annotations.Async;
import dtm.di.annotations.Component;
import dtm.di.annotations.Configuration;
import dtm.di.annotations.Profile;

@Configuration
@Profile("invalid-async-producer-dependency")
public class InvalidAsyncProducerDependencyConfiguration {

    @Async
    @Component
    public ProducedBean producedBean() {
        return new ProducedBean();
    }

    @Component
    public InvalidConsumer invalidConsumer(ProducedBean bean) {
        return new InvalidConsumer(bean);
    }

    public static final class ProducedBean {
    }

    public record InvalidConsumer(ProducedBean bean) {
    }
}
