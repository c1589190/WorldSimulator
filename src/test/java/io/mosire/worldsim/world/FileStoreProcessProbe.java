package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.state.WorldChangeSet;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.TimelineService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Subprocess for exercising actual lock release and abrupt process termination. */
public final class FileStoreProcessProbe {
  public static void main(String[] args) throws Exception {
    Path world = Path.of(args[0]);
    if (args[1].equals("hold-lock")) {
      try (var store = FileTimelineStore.open(world)) {
        System.out.println("LOCKED");
        System.out.flush();
        System.in.read();
      }
      return;
    }
    boolean eventSeries = args[1].startsWith("series-");
    String phase = eventSeries ? args[1].substring("series-".length()) : args[1];
    AtomicBoolean armed = new AtomicBoolean();
    AtomicInteger triggers = new AtomicInteger();
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID, stage -> {
      int target = eventSeries && phase.equals("node-published") ? 2 : 1;
      if (armed.get() && stage.equals(phase) && triggers.incrementAndGet() == target) Runtime.getRuntime().halt(42);
    })) {
      var timeline = new TimelineService(store, 2);
      var initial = SampleWorld.create();
      var root = timeline.createGenesis(initial, "gm:probe");
      var copy = timeline.openNext(root.ref());
      armed.set(true);
      if (eventSeries) {
        var steps = List.of(new WorldChangeSet(List.of(new WorldChange.AdvanceEvent("1936-01-01T00:00", List.of()))),
            new WorldChangeSet(List.of(new WorldChange.AdvanceEvent("1936-01-01T01:00", List.of()))));
        timeline.saveEvents(copy, steps, "gm:probe", root.stateHash(), "request", "sha256:probe");
      } else {
        timeline.apply(copy, new WorldChange.PutProvince(SampleWorld.renameProvince(initial.map(), "east", "Crash probe")));
        var receipt = new CommitReceipt("gm:probe", "request", "sha256:probe", new NodeRef(new BranchId("main"), 1));
        timeline.save(copy, 1, "gm:probe", root.stateHash(), receipt);
      }
      throw new IllegalStateException("Expected the subprocess to halt");
    }
  }
}
