package io.mosire.worldsim.app;

import io.mosire.worldsim.state.StaticWorldState;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.TimelineException;
import io.mosire.worldsim.timeline.TimelineService;
import io.mosire.worldsim.timeline.TimelineWorkingCopy;

import java.util.ArrayList;
import java.util.List;

/** Plans a GM draft into one change set per world time. */
final class WorldEventTimeline {
  record Plan(List<WorldCommand> commands, List<WorldChangeSet> steps, StaticWorldState state) {}

  private final WorldCommand.Context context;
  private final String baseEventTime;
  private final List<WorldChangeSet> steps = new ArrayList<>();
  private StaticWorldState state;

  private WorldEventTimeline(TimelineWorkingCopy copy, WorldCommand.Context context) {
    this.context = context;
    this.baseEventTime = copy.baseNode().eventTime();
    this.state = copy.baseState();
  }

  static Plan plan(TimelineWorkingCopy copy, WorldCommand.Context context, List<WorldCommand> commands) {
    var timeline = new WorldEventTimeline(copy, context);
    timeline.plan(commands);
    return new Plan(List.copyOf(commands), List.copyOf(timeline.steps), timeline.state);
  }

  private void plan(List<WorldCommand> commands) {
    String time = context.currentTime(state);
    var sameTime = new ArrayList<WorldCommand>();
    boolean requested = false;
    for (WorldCommand command : commands) {
      switch (command) {
        case WorldCommand.AdvanceTime advance -> {
          String target = advance.target(time);
          if (requested || !sameTime.isEmpty()) settle(time, sameTime);
          sameTime.clear();
          for (String due = nextDue(); due != null && due.compareTo(target) < 0; due = nextDue()) {
            settle(due, List.of());
          }
          time = target;
          requested = true;
        }
        case WorldCommand.InitializeClock initialize -> {
          if (requested) throw new TimelineException("SIMULATION_TIME_SET", "已设剧本时间，可用 advanceTime 推进");
          time = ((WorldChange.SetSimulationTime) initialize.plan(state, context)).time();
          requested = true;
        }
        default -> sameTime.add(command);
      }
    }
    if (requested || !sameTime.isEmpty()) settle(time, sameTime);
  }

  private String nextArrival() {
    return state.formations().nextArrivalTime(state.simulationTime());
  }

  private String nextDue() {
    String arrival = nextArrival();
    var rail = state.countries().railEconomy();
    String boundary = rail == null ? null : rail.nextBoundary();
    String treaty = state.civics().nextBoundary(state.simulationTime());
    return java.util.stream.Stream.of(arrival, boundary, treaty)
        .filter(java.util.Objects::nonNull).min(String::compareTo).orElse(null);
  }

  private void settle(String time, List<WorldCommand> commands) {
    if (time == null) throw new TimelineException("SIMULATION_TIME_REQUIRED", "事件节点需要先设定剧本时间");
    if (baseEventTime != null && time.compareTo(baseEventTime) <= 0) {
      throw new TimelineException("EVENT_TIME_ALREADY_COMMITTED", "该时刻已保存，请选择之后的时刻或从前一节点分叉");
    }
    StaticWorldState before = state;
    var changes = new ArrayList<WorldChange>();
    boolean hasArrivals = time.equals(nextArrival());
    if (hasArrivals) {
      commands.stream().filter(WorldEventTimeline::affectsEntry).forEach(command -> append(changes, command.plan(state, context)));
    }
    var arrivals = state.formations().planArrivals(before.simulationTime(), time, state.countries());
    var rail = state.countries().railEconomy();
    Long railPeriod = rail != null && time.equals(rail.nextBoundary()) ? rail.periodIndex() + 1 : null;
    append(changes, new WorldChange.AdvanceEvent(time, arrivals, railPeriod, before.civics().boundariesAt(before.simulationTime(), time)));
    for (WorldCommand command : commands) {
      if (!hasArrivals || !affectsEntry(command)) append(changes, command.plan(state, context));
    }
    var changeSet = new WorldChangeSet(changes);
    TimelineService.validateEvent(before, state, changeSet);
    steps.add(changeSet);
  }

  private void append(List<WorldChange> changes, WorldChange change) {
    state = new WorldChangeSet(List.of(change)).apply(state);
    changes.add(change);
  }

  private static boolean affectsEntry(WorldCommand command) {
    return command instanceof WorldCommand.SetTransitRight
        || command instanceof WorldCommand.PersistedChange persisted
        && (persisted.change() instanceof WorldChange.SetTerritory || persisted.change() instanceof WorldChange.PutCountry);
  }
}
