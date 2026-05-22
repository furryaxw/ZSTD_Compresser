package top.furryaxw.zstd_compresser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DictCache {

    private static final Map<Long, byte[]> cache = new ConcurrentHashMap<>();

    public static byte[] get(long dictId) {
        return cache.get(dictId);
    }

    public static void put(long dictId, byte[] data) {
        cache.put(dictId, data);
    }

    public static boolean contains(long dictId) {
        return cache.containsKey(dictId);
    }
}
