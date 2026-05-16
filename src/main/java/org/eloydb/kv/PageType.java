package org.eloydb.kv;

/** M1 page type tag. */
public enum PageType {
  META(1),
  INTERNAL(2),
  LEAF(3),
  OVERFLOW(4),
  FREELIST(5),
  WAL_INDEX(6);

  private final byte code;

  PageType(int code) {
    this.code = (byte) code;
  }

  byte code() {
    return code;
  }

  static PageType fromCode(byte code) {
    for (PageType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new KvException(
        ErrorCode.CORRUPTED_PAGE, "unknown page type " + Byte.toUnsignedInt(code));
  }
}
