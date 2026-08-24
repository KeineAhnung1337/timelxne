package dev.timelxne.timeline;

@FunctionalInterface
public interface TooltipProvider {
    TooltipContent tooltipFor(TimelineEvent event);
}
