package dtm.di.testsupport;

import dtm.di.prototypes.async.AsyncComponent;

public record AsyncGenericProducerConsumer(AsyncComponent<GenericProcessor<BarPayload>> bar) {
}
