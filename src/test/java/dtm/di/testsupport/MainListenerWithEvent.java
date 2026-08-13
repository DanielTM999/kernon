package dtm.di.testsupport;

import dtm.di.annotations.Component;
import dtm.di.annotations.Singleton;
import dtm.di.annotations.event.Event;
import dtm.di.annotations.event.EventListener;

@Singleton
@Component
@Event
public class MainListenerWithEvent {

    @EventListener
    public void onPing(MainPingEvent event) {
        Probe.record("MainListenerWithEvent:" + event.message());
    }
}
