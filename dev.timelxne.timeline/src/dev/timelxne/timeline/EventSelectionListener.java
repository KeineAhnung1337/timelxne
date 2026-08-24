package dev.timelxne.timeline;

@FunctionalInterface
public interface EventSelectionListener {
    void eventSelectionChanged(EventSelectionChangeEvent event);
}
