package io.mosire.worldsim.world;

import io.mosire.worldsim.timeline.TimelineException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Filesystem operations used by the file store's commit protocol. */
public final class DurableFiles {
  private DurableFiles() {}

  public static void directories(Path directory) {
    try {
      if (Files.exists(directory)) return;
      Path parent = directory.getParent();
      if (parent != null) directories(parent);
      Files.createDirectory(directory);
      if (parent != null) syncDirectory(parent);
    } catch (IOException e) {
      throw new TimelineException("WORLD_IO_ERROR", "无法创建目录: " + directory, e);
    }
  }

  public static void writeNew(Path file, String text) {
    directories(file.getParent());
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    } catch (IOException e) {
      throw new TimelineException("WORLD_IO_ERROR", "无法写入世界文件: " + file, e);
    }
    syncDirectory(file.getParent());
  }

  public static void moveAtomic(Path from, Path to, boolean replace) {
    directories(to.getParent());
    try {
      if (replace) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
      }
      syncDirectory(to.getParent());
      if (!from.getParent().equals(to.getParent())) syncDirectory(from.getParent());
    } catch (AtomicMoveNotSupportedException e) {
      throw new TimelineException("WORLD_ATOMIC_MOVE_UNSUPPORTED", "文件系统不支持原子发布: " + to, e);
    } catch (IOException e) {
      throw new TimelineException("WORLD_IO_ERROR", "无法原子发布世界文件: " + to, e);
    }
  }

  public static void syncDirectory(Path directory) {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException | UnsupportedOperationException e) {
      throw new TimelineException("WORLD_DIRECTORY_SYNC_UNSUPPORTED", "无法持久化目录项: " + directory, e);
    }
  }

  public static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TimelineException("WORLD_FILE_READ_FAILED", "无法读取世界文件: " + path, e);
    }
  }
}
