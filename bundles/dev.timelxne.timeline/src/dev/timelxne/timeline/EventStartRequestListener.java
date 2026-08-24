package dev.timelxne.timeline;

@FunctionalInterface
public interface EventStartRequestListener {
    void eventStartRequested(EventStartRequestEvent event);
}
