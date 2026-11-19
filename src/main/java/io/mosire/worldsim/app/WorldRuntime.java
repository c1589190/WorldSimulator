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
    try (FileTimelineStore store = FileTimelineStore.open(worldPath)) {
      WorldApplicationService app = new WorldApplicationService(store);
      app.overview(null); // Load and validate the committed head before exposing any tools.
      AgentPermissionSet permissions = AgentPermissionSet.builder(AccessToken.DEFAULT)
          .allowAll().destructiveAllowed(true).sensitiveAllowed(true)
          .roles(RoleSet.of("GM"))
          .resourceScopes(ResourceScopeMap.of("world", ResourceScope.of("/" + store.worldId())))
          .build();
      ToolContext caller = ToolContext.of(AccessToken.DEFAULT, permissions, AgentIdentity.main(CommandMode.FULL));
      CountDownLatch ended = new CountDownLatch(1);
      InputStream monitored = new EndNotifyingInputStream(input, ended);
      try (WorldViewerServer viewer = viewerPort == null ? null : WorldViewerServer.start(app, viewerPort);
           AgentToMcpServer server = AgentToMcpServer.start(
               WorldTools.create(app), "worldsim", "0.1.0", caller, monitored, output)) {
        ended.await();
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
