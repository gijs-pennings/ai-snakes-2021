package negasnake;

import java.util.Arrays;

import static negasnake.Constants.INF;
import static negasnake.Constants.TABLE_SIZE;

/**
 * Fixed size hash table for move ordering using open addressing and linear probing.
 */
public final class HashTable {

    private final long[] array = new long[TABLE_SIZE];

    public void clear() {
        Arrays.fill(array, 0);
    }

    /**
     * Returns value that is mapped to by {@code key > 0} (or {@code INF}).
     */
    public int get(final int key) {
        for (int i = key; true; i++) {
            i %= TABLE_SIZE;
            final long x = array[i];
            if (x == 0) return INF;
            if (key == (int) x) return (int) (x >>> 32);
        }
    }

    /**
     * Associates {@code val} with {@code key > 0}. Any existing pairing is overwritten.
     */
    public void set(final int key, final int val) {
        final long x = key | (long) val << 32;
        for (int i = key; true; i++) {
            i %= TABLE_SIZE;
            final int k = (int) array[i];
            if (k == 0 || k == key) { array[i] = x; return; }
        }
    }

}
