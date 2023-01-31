package org.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public class InputGenerator {
    private static final byte[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREFIX = "{\"haystack\":[\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HAYSTACK_SEP = "\",\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HAYSTACK_NEEDLE_SEP = "\"],\"needle\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SUFFIX = "\"}".getBytes(StandardCharsets.UTF_8);

    private final int haystackListSize;
    private final int haystackStringSize;
    private final int needleSize;
    private final List<byte[]> haystack;
    private final int needleIndex;
    private final byte[] preparedJson;

    public InputGenerator(int haystackListSize, int haystackStringSize, int needleSize) {
        this.haystackListSize = haystackListSize;
        this.haystackStringSize = haystackStringSize;
        this.needleSize = needleSize;

        haystack = Stream.generate(() -> randomString(haystackStringSize))
                .limit(haystackListSize)
                .toList();
        needleIndex = PREFIX.length + HAYSTACK_SEP.length * (haystackListSize - 1) + haystackListSize * haystackStringSize + HAYSTACK_NEEDLE_SEP.length;
        int jsonLength = needleIndex + needleSize + SUFFIX.length;
        preparedJson = new byte[jsonLength];
        ByteBuffer preparedWrap = ByteBuffer.wrap(preparedJson);
        preparedWrap.put(PREFIX);
        for (int i = 0; i < haystack.size(); i++) {
            if (i != 0) {
                preparedWrap.put(HAYSTACK_SEP);
            }
            preparedWrap.put(haystack.get(i));
        }
        preparedWrap.put(HAYSTACK_NEEDLE_SEP);
        preparedWrap.put(new byte[needleSize]); // leave needle empty
        preparedWrap.put(SUFFIX);
        if (preparedWrap.hasRemaining()) {
            throw new AssertionError();
        }
    }

    public byte[] generate() {
        int listIndex = ThreadLocalRandom.current().nextInt(haystackListSize);
        int stringIndex = ThreadLocalRandom.current().nextInt(haystackStringSize - needleSize);
        byte[] out = preparedJson.clone();
        System.arraycopy(haystack.get(listIndex), stringIndex, out, needleIndex, needleSize);
        return out;
    }

    private static byte[] randomString(int haystackStringSize) {
        byte[] arr = new byte[haystackStringSize];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ALPHABET[ThreadLocalRandom.current().nextInt(ALPHABET.length)];
        }
        return arr;
    }
}
