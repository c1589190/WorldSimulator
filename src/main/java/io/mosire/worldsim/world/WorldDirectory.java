package io.mosire.worldsim.world;

import io.mosire.worldsim.timeline.TimelineException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Owns the operating-system lock for one world directory. */
public final class WorldDirectory implements AutoCloseable {
  private final Path root;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private boolean closed;

  private WorldDirectory(Path root, FileChannel lockChannel, FileLock lock) {
    this.root = root;
    this.lockChannel = lockChannel;
    this.lock = lock;
  }

  public static WorldDirectory open(Path path) {
    return open(path, false);
  }

  /** Creation is reserved for explicit initialization or migration. */
  public static WorldDirectory open(Path path, boolean create) {
    Objects.requireNonNull(path, "path");
    Path absolute = path.toAbsolutePath().normalize();
    FileChannel channel = null;
    try {
      if (create) {
        Files.createDirectories(absolute);
      }
      if (!Files.isDirectory(absolute)) {
        throw new TimelineException("WORLD_DIRECTORY_MISSING", "世界目录不存在: " + absolute);
      }
      Path root = absolute.toRealPath();
      Path lockPath = root.resolve("session.lock");
      if (Files.isSymbolicLink(lockPath)) {
        throw new TimelineException("WORLD_UNSAFE_PATH", "锁文件不能是符号链接: " + lockPath);
      }
      channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) {
        channel.close();
        throw new TimelineException("WORLD_IN_USE", "世界已被另一个实例打开: " + root);
      }
      return new WorldDirectory(root, channel, lock);
    } catch (IOException e) {
      if (channel != null) {
        try { channel.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
      }
      throw new TimelineException("WORLD_OPEN_FAILED", "无法打开世界目录: " + absolute, e);
    }
  }

  public Path root() {
    ensureOpen();
    return root;
  }

  /** Resolve a stored relative path and reject traversal and symlinks below the world root. */
  public Path resolve(String relative) {
    ensureOpen();
    Path part;
    try {
      part = Path.of(Objects.requireNonNull(relative, "relative"));
    } catch (RuntimeException e) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "非法世界文件路径", e);
    }
    if (part.isAbsolute() || part.toString().isBlank()) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "世界文件路径必须是非空相对路径: " + relative);
    }
    Path resolved = root.resolve(part).normalize();
    if (!resolved.startsWith(root) || resolved.equals(root)) {
      throw new TimelineException("WORLD_UNSAFE_PATH", "世界文件路径越界: " + relative);
    }
    Path current = root;
    for (Path segment : root.relativize(resolved)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new TimelineException("WORLD_UNSAFE_PATH", "世界文件路径包含符号链接: " + relative);
      }
    }
    return resolved;
  }

  /** Existing import sources must stay inside world/imports. */
  public Path requireImportFile(Path source) {
    ensureOpen();
    try {
      Path imports = resolve("imports").toRealPath();
      Path real = source.toAbsolutePath().normalize().toRealPath();
      if (!imports.startsWith(root) || !real.startsWith(imports) || !Files.isRegularFile(real)) {
        throw new TimelineException("WORLD_IMPORT_OUTSIDE", "导入源必须是 world/imports 下的文件: " + source);
      }
      return real;
    } catch (IOException e) {
      throw new TimelineException("WORLD_IMPORT_NOT_FOUND", "无法读取世界导入文件: " + source, e);
    }
  }

  public Path requireImportDirectory(Path source) {
    ensureOpen();
    try {
      Path imports = resolve("imports").toRealPath();
      Path real = source.toAbsolutePath().normalize().toRealPath();
      if (!imports.startsWith(root) || !real.startsWith(imports) || !Files.isDirectory(real)) {
        throw new TimelineException("WORLD_IMPORT_OUTSIDE", "导入源必须在 world/imports 下: " + source);
      }
      try (var paths = Files.walk(real)) {
        if (paths.anyMatch(Files::isSymbolicLink)) {
          throw new TimelineException("WORLD_UNSAFE_PATH", "导入目录包含符号链接: " + source);
        }
      }
      return real;
    } catch (IOException e) {
      throw new TimelineException("WORLD_IMPORT_NOT_FOUND", "无法读取世界导入目录: " + source, e);
    }
  }

  private void ensureOpen() {
    if (closed || !lock.isValid()) {
      throw new TimelineException("WORLD_CLOSED", "世界目录已关闭: " + root);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      lock.release();
      lockChannel.close();
    } catch (IOException e) {
      throw new TimelineException("WORLD_CLOSE_FAILED", "关闭世界锁失败: " + root, e);
    }
  }
}
