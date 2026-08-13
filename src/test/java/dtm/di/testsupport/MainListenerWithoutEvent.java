package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Singleton;
import dtm.di.annotations.event.EventListener;

@Singleton
@Component
public class MainListenerWithoutEvent {

    @EventListener
    public void onPing(MainPingEvent event) {
        Probe.record("MainListenerWithoutEvent:" + event.message());
    }
}
