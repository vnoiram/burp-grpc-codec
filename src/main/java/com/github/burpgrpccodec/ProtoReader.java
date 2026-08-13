package com.github.burpgrpccodec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ProtoReader {
    private final byte[] bytes;
    private int offset;

    ProtoReader(byte[] bytes) {
        this.bytes = bytes;
    }

    boolean exhausted() {
        return offset == bytes.length;
    }

    long readVarint() {
        long value = 0;
        int shift = 0;
        while (shift < 64) {
            ensure(1);
            int b = bytes[offset++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("invalid protobuf varint");
    }

    long readFixed64() {
        ensure(8);
        long value = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        offset += 8;
        return value;
    }

    long readFixed32() {
        ensure(4);
        long value = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
        offset += 4;
        return value;
    }

    byte[] readBytes() {
        long length = readVarint();
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("length-delimited field is too large");
        }
        ensure((int) length);
        byte[] value = new byte[(int) length];
        System.arraycopy(bytes, offset, value, 0, value.length);
        offset += value.length;
        return value;
    }

    private void ensure(int count) {
        if (count < 0 || count > bytes.length - offset) {
            throw new IllegalArgumentException("truncated protobuf payload");
        }
    }
}
