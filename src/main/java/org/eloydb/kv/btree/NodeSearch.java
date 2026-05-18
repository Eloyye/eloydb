package org.eloydb.kv.btree;

import java.util.Arrays;
import java.util.List;
import org.eloydb.kv.storage.SlottedPage.InternalEntry;
import org.eloydb.kv.storage.SlottedPage.LeafEntry;

final class NodeSearch {
  private NodeSearch() {}

  static int findLeafSlot(List<LeafEntry> entries, byte[] key) {
    int lo = 0;
    int hi = entries.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      int cmp = Arrays.compareUnsigned(entries.get(mid).key(), key);
      if (cmp < 0) {
        lo = mid + 1;
      } else if (cmp > 0) {
        hi = mid;
      } else {
        return mid;
      }
    }
    return -(lo + 1);
  }

  static int findChildIndex(List<InternalEntry> entries, byte[] key) {
    int lo = 0;
    int hi = entries.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (Arrays.compareUnsigned(entries.get(mid).separator(), key) <= 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }
}
