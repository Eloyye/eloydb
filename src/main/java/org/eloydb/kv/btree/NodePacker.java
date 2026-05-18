package org.eloydb.kv.btree;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import org.eloydb.kv.btree.CowBTree.ApplyResult;
import org.eloydb.kv.btree.CowBTree.Replaced;
import org.eloydb.kv.btree.CowBTree.Split;
import org.eloydb.kv.storage.BufferPool;
import org.eloydb.kv.storage.Page;
import org.eloydb.kv.storage.PageType;
import org.eloydb.kv.storage.SlottedPage.InternalBuilder;
import org.eloydb.kv.storage.SlottedPage.InternalEntry;
import org.eloydb.kv.storage.SlottedPage.LeafBuilder;
import org.eloydb.kv.storage.SlottedPage.LeafEntry;

final class NodePacker {
  private final BufferPool pages;
  private final LongSupplier lsnSupplier;

  NodePacker(BufferPool pages, LongSupplier lsnSupplier) {
    this.pages = pages;
    this.lsnSupplier = lsnSupplier;
  }

  ApplyResult packLeaf(List<LeafEntry> entries) {
    LeafBuilder builder = leafBuilder(entries);
    if (builder.fits()) {
      return new Replaced(writeLeaf(builder));
    }

    int midpoint = chooseSplit(entries, LeafBuilder::costOf);
    List<LeafEntry> left = entries.subList(0, midpoint);
    List<LeafEntry> right = entries.subList(midpoint, entries.size());

    long leftPid = writeFittingLeaf(left, "leaf left half still overflows after split");
    long rightPid = writeFittingLeaf(right, "leaf right half still overflows after split");
    byte[] separator = Arrays.copyOf(right.get(0).key(), right.get(0).key().length);
    return new Split(leftPid, separator, rightPid);
  }

  ApplyResult packInternal(long leftmost, List<InternalEntry> entries) {
    InternalBuilder builder = internalBuilder(leftmost, entries);
    if (builder.fits()) {
      return new Replaced(writeInternal(builder));
    }

    int midpoint = chooseSplit(entries, InternalBuilder::costOf);
    InternalEntry promoted = entries.get(midpoint);
    List<InternalEntry> leftEntries = entries.subList(0, midpoint);
    List<InternalEntry> rightEntries = entries.subList(midpoint + 1, entries.size());

    long leftPid =
        writeFittingInternal(
            leftmost, leftEntries, "internal left half still overflows after split");
    long rightPid =
        writeFittingInternal(
            promoted.childPid(), rightEntries, "internal right half still overflows after split");
    byte[] separator = Arrays.copyOf(promoted.separator(), promoted.separator().length);
    return new Split(leftPid, separator, rightPid);
  }

  private long writeFittingLeaf(List<LeafEntry> entries, String errorMessage) {
    LeafBuilder builder = leafBuilder(entries);
    if (!builder.fits()) {
      throw new IllegalStateException(errorMessage);
    }
    return writeLeaf(builder);
  }

  private long writeFittingInternal(
      long leftmost, List<InternalEntry> entries, String errorMessage) {
    InternalBuilder builder = internalBuilder(leftmost, entries);
    if (!builder.fits()) {
      throw new IllegalStateException(errorMessage);
    }
    return writeInternal(builder);
  }

  private long writeLeaf(LeafBuilder builder) {
    long pid = pages.allocate(PageType.LEAF);
    pages.write(new Page(PageType.LEAF, lsnSupplier.getAsLong(), pid, builder.build()));
    return pid;
  }

  private long writeInternal(InternalBuilder builder) {
    long pid = pages.allocate(PageType.INTERNAL);
    pages.write(new Page(PageType.INTERNAL, lsnSupplier.getAsLong(), pid, builder.build()));
    return pid;
  }

  private static LeafBuilder leafBuilder(List<LeafEntry> entries) {
    LeafBuilder builder = new LeafBuilder();
    entries.forEach(builder::add);
    return builder;
  }

  private static InternalBuilder internalBuilder(long leftmost, List<InternalEntry> entries) {
    InternalBuilder builder = new InternalBuilder(leftmost);
    entries.forEach(builder::add);
    return builder;
  }

  private static <T> int chooseSplit(List<T> entries, ToIntFunction<T> costOf) {
    int total = 0;
    for (T entry : entries) {
      total += costOf.applyAsInt(entry);
    }
    int target = total / 2;
    int running = 0;
    for (int i = 0; i < entries.size(); i++) {
      running += costOf.applyAsInt(entries.get(i));
      if (running >= target && i > 0) {
        return i;
      }
    }
    return Math.max(1, entries.size() / 2);
  }
}
