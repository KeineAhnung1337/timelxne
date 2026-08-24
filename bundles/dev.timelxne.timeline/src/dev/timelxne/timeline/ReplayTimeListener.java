package dev.timelxne.timeline;

@FunctionalInterface
public interface ReplayTimeListener {
    void replayTimeChanged(ReplayTimeChangeEvent event);
}
