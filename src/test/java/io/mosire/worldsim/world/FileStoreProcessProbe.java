package io.mosire.worldsim.world;

import io.mosire.worldsim.address.BranchId;
import io.mosire.worldsim.address.NodeRef;
import io.mosire.worldsim.cli.SampleWorld;
import io.mosire.worldsim.state.WorldChange;
import io.mosire.worldsim.timeline.CommitReceipt;
import io.mosire.worldsim.timeline.TimelineService;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
    String phase = args[1];
    AtomicBoolean armed = new AtomicBoolean();
    try (var store = FileTimelineStore.create(world, SampleWorld.WORLD_ID, stage -> {
      if (armed.get() && stage.equals(phase)) Runtime.getRuntime().halt(42);
    })) {
      var timeline = new TimelineService(store, 2);
      var initial = SampleWorld.create();
      var root = timeline.createGenesis(initial, "gm:probe");
      var copy = timeline.openNext(root.ref());
      timeline.apply(copy, new WorldChange.PutProvince(SampleWorld.renameProvince(initial.map(), "east", "Crash probe")));
      var receipt = new CommitReceipt("gm:probe", "request", "sha256:probe", new NodeRef(new BranchId("main"), 1));
      armed.set(true);
      timeline.save(copy, 1, "gm:probe", root.stateHash(), receipt);
      throw new IllegalStateException("Expected the subprocess to halt");
    }
  }
}
