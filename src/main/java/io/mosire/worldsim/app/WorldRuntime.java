package io.mosire.worldsim.app;

import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.worldsim.app.viewer.WorldViewerServer;
import io.mosire.worldsim.world.FileTimelineStore;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** GM stdio MCP and local viewer entry points for the world in the working directory. */
public final class WorldRuntime {
  private WorldRuntime() {}

  public static void run(Path worldPath, InputStream input, OutputStream output) throws InterruptedException {
    run(worldPath, input, output, null);
  }

  public static void run(Path worldPath, InputStream input, OutputStream output, Integer viewerPort)
      throws InterruptedException {
    run(worldPath, input, output, viewerPort, null, null, null);
  }

  public static void run(Path worldPath, InputStream input, OutputStream output, Integer viewerPort,
                         String turn, String country, String decisionMaker) throws InterruptedException {
    if ((turn != null) != (country != null || decisionMaker != null)
        || country != null && decisionMaker != null || turn != null && viewerPort != null) {
      throw new IllegalArgumentException("决策进程需要回合及唯一的参与身份，并且不启动 GM 查看器");
    }
    try (FileTimelineStore store = FileTimelineStore.open(worldPath)) {
      WorldApplicationService app = new WorldApplicationService(store);
      var session = turn == null ? null : app.decisionSession(turn, country != null ? country : decisionMaker, country != null);
      if (session == null) app.overview(null);
      var tools = session == null ? WorldTools.create(app) : WorldTools.decisions(app, session);
      AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
          .allowAll().destructiveAllowed(true).sensitiveAllowed(true)
          .roles(RoleSet.of(session == null ? "GM" : "DECISION"))
          .resourceScopes(ResourceScopeMap.of("world", ResourceScope.of("/" + store.worldId())))
          .build();
      ToolContext caller = ToolContext.of(AccessToken.DEFAULT, permissions, AgentIdentity.main(CommandMode.FULL));
      CountDownLatch ended = new CountDownLatch(1);
      InputStream monitored = new EndNotifyingInputStream(input, ended);
      try (WorldViewerServer viewer = viewerPort == null ? null : WorldViewerServer.start(app, viewerPort);
           AgentToMcpServer server = AgentToMcpServer.start(
               tools, "worldsim", "0.1.0", caller, monitored, output)) {
        ended.await();
      }
    }
  }

  public static void viewDecision(Path worldPath, int port, String turn, String actor) throws InterruptedException {
    if (turn == null || actor == null) throw new IllegalArgumentException("决策查看器需要回合及身份");
    try (var store = FileTimelineStore.open(worldPath)) {
      var app = new WorldApplicationService(store);
      var session = app.decisionSession(turn, actor, false);
      if (!session.scoped()) throw new IllegalArgumentException("决策查看器需要已开启的可见面");
      var access = new WorldViewerServer.DecisionAccess(session.turn().node().label(), () -> app.decisionBrief(session),
          () -> app.decisionMarkers(session), address -> app.decisionLookup(session, address),
          query -> app.decisionSearch(session, query, 50));
      try (var viewer = WorldViewerServer.start(app, port, access)) {
        new CountDownLatch(1).await();
      }
    }
  }

  public static void view(Path worldPath, int port) throws InterruptedException {
    try (FileTimelineStore store = FileTimelineStore.open(worldPath)) {
      WorldApplicationService app = new WorldApplicationService(store);
      app.savedMap(null);
      try (WorldViewerServer viewer = WorldViewerServer.start(app, port)) {
        new CountDownLatch(1).await();
      }
    }
  }

  private static final class EndNotifyingInputStream extends FilterInputStream {
    private final CountDownLatch ended;
    private EndNotifyingInputStream(InputStream in, CountDownLatch ended) { super(in); this.ended = ended; }
    @Override public int read() throws IOException {
      try { int value = in.read(); if (value < 0) ended.countDown(); return value; }
      catch (IOException e) { ended.countDown(); throw e; }
    }
    @Override public int read(byte[] data, int offset, int length) throws IOException {
      try { int value = in.read(data, offset, length); if (value < 0) ended.countDown(); return value; }
      catch (IOException e) { ended.countDown(); throw e; }
    }
    @Override public void close() throws IOException { try { super.close(); } finally { ended.countDown(); } }
  }
}
