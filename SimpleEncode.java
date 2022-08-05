import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class SimpleEncode {
    private static final Logger logger = Logger.getLogger("SimpleEncode");

    protected final static String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
        "abcdefghijklmnopqrstuvwxyz" +
        "0123456789+-"; // 26+26+10+2=64

    protected final static char[] ALPHABET_ARRAY = ALPHABET.toCharArray();

    protected final static int[] ALPHABET_REVERSE = new int[123];

    protected final static int SPLIT_SIZE = 4096;

    static {
        for (int i = 0; i < 123; ++i) {
            ALPHABET_REVERSE[i] = -1;
        }
        for (char c = 'A'; c <= 'Z'; ++c) {
            ALPHABET_REVERSE[c] = c - 'A';
        }
        for (char c = 'a'; c <= 'z'; ++c) {
            ALPHABET_REVERSE[c] = c - 'a' + 26;
        }
        for (char c = '0'; c <= '9'; ++c) {
            ALPHABET_REVERSE[c] = c - '0' + 26 + 26;
        }
        ALPHABET_REVERSE['+'] = 62;
        ALPHABET_REVERSE['-'] = 63;
    }

    protected static boolean isControlCharacter(char c) {
        return c == '?' || c == '!' || c == '@';
    }

    public static String encode(byte[] data) {
        return encode(data, 0, data.length);
    }

    public static String encode(byte[] data, int startIndexInclusive, int endIndexExclusive) {
        System.out.println("Encoding " + data.length + " bytes, single thread");
        return encodeImpl(data, startIndexInclusive, endIndexExclusive);
    }

    // ~ = 64
    // ! = ~~
    // ? = ~~~
    protected static String encodeImpl(byte[] data, int startIndexInclusive, int endIndexExclusive) {
        StringBuilder builder = new StringBuilder(endIndexExclusive - startIndexInclusive);
        for (int i = startIndexInclusive; i < endIndexExclusive; ++i) {
            short ub = (short) (data[i] + 128);
            if (ub >= (short) 192) {
                builder.append("?");
            }
            else if (ub >= (short) 128) {
                builder.append("!");
            }
            else if (ub >= (short) 64) {
                builder.append("@");
            }
            builder.append(ALPHABET_ARRAY[ub % (byte) 64]);
        }
        return builder.toString();
    }

    public static byte[] decode(String data) throws InvalidDataException {
        return decode(data, 0, data.length());
    }

    public static byte[] decode(String data, int startIndexInclusive, int endIndexExclusive) throws InvalidDataException {
        System.out.println("Decoding " + data.length() + " bytes, single thread");
        return decodeImpl(data, startIndexInclusive, endIndexExclusive);
    }

    protected static byte[] decodeImpl(String data, int startIndexInclusive, int endIndexExclusive) throws InvalidDataException {
        short offset = 0;

        // Calculate the size of the output buffer.
        int size = 0;
        int n = 0;
        for (int i = startIndexInclusive; i < endIndexExclusive; ++i) {
            if (!isControlCharacter(data.charAt(i))) {
                ++size;
            }
        }

        byte[] ret = new byte[size];

        for (int i = startIndexInclusive; i < endIndexExclusive; ++i) {
            final char c = data.charAt(i);
            if (isControlCharacter(c)) {
                if (c == '?') {
                    offset = 192;
                }
                else if (c == '!') {
                    offset = 128;
                }
                else {
                    offset = 64;
                }
            }
            else {
                int index = ALPHABET_REVERSE[c]; // alphabetIndexOf(c);
                if (index == -1) {
                    throw new InvalidDataException("Invalid character: " + c);
                }
                byte b = (byte) index;
                ret[n++] = (byte) (b + offset - 128);
                offset = 0;
            }
        }
        return ret;
    }

    protected static WorkerInfo calculateWorkerInfo(int totalLength) {

        final int availableProcessors = 4 * Runtime.getRuntime().availableProcessors();

        // Confirm required workers.
        int workers = totalLength / SPLIT_SIZE;

        // One more worker for remaining data, if any.
        if (totalLength % SPLIT_SIZE != 0) {
            ++workers;
        }

        // Confirm split size.
        int splitSize = SPLIT_SIZE;

        // Workers exceed available processors?
        // Re-arrange works.
        if (workers > availableProcessors) {
            workers = availableProcessors;
            splitSize = totalLength / workers;

            // One more worker for remaining data, if any.
            if (workers * splitSize < totalLength) {
                ++workers;
            }
        }

        return new WorkerInfo(workers, splitSize);
    }

    public static String[] encodeFast(final byte[] data, int timeoutMilliseconds)
        throws IllegalArgumentException, InterruptedException, TimeoutException {
        final int totalLength = data.length;

        if (totalLength == 0) {
            return new String[0];
        }

        WorkerInfo workerInfo = calculateWorkerInfo(totalLength);
        final int workers = workerInfo.workers();
        final int splitSize = workerInfo.splitSize();

        System.out.println("Encoding " + totalLength + " bytes, " + workers + " workers, " + splitSize + " bytes per worker");

        final String[] results = new String[workers];
        final ExecutorService pool = Executors.newFixedThreadPool(workers);

        for (int i = 0; i < workers; ++i) {
            final int startIndexInclusive = i * splitSize;
            final int endIndexExclusive = Math.min(totalLength, (i + 1) * splitSize);
            final int resultIndex = i;
            pool.execute(() -> {
                // The lock is not necessary because they are writing to different locations.
                results[resultIndex] = encodeImpl(data, startIndexInclusive, endIndexExclusive);
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(timeoutMilliseconds, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timeout.");
        }
        return results;
    }

    public static byte[][] decodeFast(String data, int timeoutMilliseconds)
        throws IllegalArgumentException, InterruptedException, TimeoutException, InvalidDataException {
        final int totalLength = data.length();

        if (totalLength == 0) {
            return new byte[0][];
        }

        WorkerInfo workerInfo = calculateWorkerInfo(totalLength);
        final int workers = workerInfo.workers();
        final int splitSize = workerInfo.splitSize();

        System.out.println("Decoding " + totalLength + " bytes, " + workers + " workers, " + splitSize + " bytes per worker");
        final byte[][] results = new byte[workers][];

        final ExecutorService pool = Executors.newFixedThreadPool(workers);
        final AtomicBoolean isInvalidDataAtomic = new AtomicBoolean(false);
        boolean padNext = false;

        for (int i = 0; i < workers; ++i) {
            int startIndexInclusive = i * splitSize;
            int endIndexExclusive = Math.min(totalLength, (i + 1) * splitSize);

            // Fix index if hit control character.
            if (padNext) {
                ++startIndexInclusive;
                padNext = false;
            }
            if (isControlCharacter(data.charAt(endIndexExclusive - 1))) {
                ++endIndexExclusive;
                padNext = true;
            }

            final int l = startIndexInclusive;
            final int r = endIndexExclusive;
            final int resultIndex = i;
            pool.execute(() -> {
                // The lock is not necessary because they are writing to different locations.
                try {
                    results[resultIndex] = decodeImpl(data, l, r);
                } catch (InvalidDataException e) {
                    isInvalidDataAtomic.set(true);
                }
            });
        }
        pool.shutdown();

        if (isInvalidDataAtomic.get()) {
            throw new InvalidDataException("Invalid data.");
        }
        if (!pool.awaitTermination(timeoutMilliseconds, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timeout.");
        }

        return results;
    }

    public static byte[] flattenResults(byte[][] results) {
        int size = 0;
        for (byte[] bytes : results) {
            size += bytes.length;
        }
        byte[] ret = new byte[size];
        int offset = 0;
        for (byte[] bytes : results) {
            System.arraycopy(bytes, 0, ret, offset, bytes.length);
            offset += bytes.length;
        }
        return ret;
    }

    public static class InvalidDataException extends Exception {
        public InvalidDataException(String message) {
            super(message);
        }
    }

    protected record WorkerInfo(int workers, int splitSize) {
    }
}
