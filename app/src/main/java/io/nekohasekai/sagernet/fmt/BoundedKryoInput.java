package io.nekohasekai.sagernet.fmt;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import java.nio.ByteBuffer;

/** Checks allocation lengths before Kryo allocates; preserves its legacy string encoding. */
public final class BoundedKryoInput extends ByteBufferInput {
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    public static final int MAX_COLLECTION = 100_000;

    public static final class LimitException extends KryoException {
        public LimitException() { super("Serialized configuration exceeds decoding limits or has an invalid length"); }
    }

    public static final class Budget {
        private long remaining = 64L * 1024 * 1024;
        public void reserve(long bytes) {
            if (bytes < 0 || bytes > remaining) throw new LimitException();
            remaining -= bytes;
        }
    }

    private final Budget budget;

    public BoundedKryoInput(byte[] bytes, Budget budget) {
        super(wrap(bytes));
        this.budget = budget;
        budget.reserve(64); // Input object; the original byte array is already owned by the caller.
    }

    private static ByteBuffer wrap(byte[] bytes) {
        if (bytes.length > MAX_PAYLOAD) throw new LimitException();
        return ByteBuffer.wrap(bytes);
    }

    private int arrayLength(int count, int minimumInputWidth, int allocationWidth) {
        long allocation = (long) count * allocationWidth;
        if (count < 0 || allocation > MAX_PAYLOAD || (long) count * minimumInputWidth > limit() - position())
            throw new LimitException();
        budget.reserve(allocation + 32);
        return count;
    }

    public int readCollectionCount(int minimumInputWidth) {
        int count = readInt();
        if (count < 0 || count > MAX_COLLECTION || (long) count * minimumInputWidth > limit() - position())
            throw new LimitException();
        budget.reserve(64L * count + 32);
        return count;
    }

    private void checkString() {
        int start = position();
        long count;
        if (start >= limit()) throw new KryoException("Truncated serialized string");
        if ((byteBuffer.get(start) & 0x80) == 0) {
            int end = start;
            while (end < limit() && (byteBuffer.get(end) & 0x80) == 0) end++;
            if (end == limit()) throw new KryoException("Truncated serialized string");
            count = (long) end - start + 1;
        } else {
            try {
                int encoded = readVarIntFlag(true);
                if (encoded < 0) throw new LimitException();
                count = Math.max(0L, (long) encoded - 1);
                // Each UTF-16 code unit needs at least one encoded byte.
                if (count > MAX_PAYLOAD / 2) throw new LimitException();
                if (count > limit() - position()) throw new KryoException("Truncated serialized string");
            } finally {
                setPosition(start);
            }
        }
        if (count > MAX_PAYLOAD / 2) throw new LimitException();
        // Covers Kryo scratch growth plus the returned String/StringBuilder and copies.
        budget.reserve(count * 8 + 64);
    }

    @Override public String readString() { checkString(); return super.readString(); }
    @Override public StringBuilder readStringBuilder() { checkString(); return super.readStringBuilder(); }
    @Override public byte[] readBytes(int count) { return super.readBytes(arrayLength(count, 1, 1)); }
    @Override public int[] readInts(int count) { return super.readInts(arrayLength(count, 4, 4)); }
    @Override public int[] readInts(int count, boolean positive) { return super.readInts(arrayLength(count, 1, 4), positive); }
    @Override public long[] readLongs(int count) { return super.readLongs(arrayLength(count, 8, 8)); }
    @Override public long[] readLongs(int count, boolean positive) { return super.readLongs(arrayLength(count, 1, 8), positive); }
    @Override public float[] readFloats(int count) { return super.readFloats(arrayLength(count, 4, 4)); }
    @Override public double[] readDoubles(int count) { return super.readDoubles(arrayLength(count, 8, 8)); }
    @Override public short[] readShorts(int count) { return super.readShorts(arrayLength(count, 2, 2)); }
    @Override public char[] readChars(int count) { return super.readChars(arrayLength(count, 2, 2)); }
    @Override public boolean[] readBooleans(int count) { return super.readBooleans(arrayLength(count, 1, 1)); }
}
