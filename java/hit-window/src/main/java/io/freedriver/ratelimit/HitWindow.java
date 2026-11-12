package io.freedriver.ratelimit;

/**
 * A sliding window of hits. Production is Redis, and a restart may drop the keys.
 * The in-memory implementation is not the production bean.
 */
public interface HitWindow {

    /** A token for this hit, or null when the window is already full. */
    String tryAdd(String key, long nowMs, long windowMs, int max);

    /** Records a hit even when the window is already full. */
    String add(String key, long nowMs, long windowMs);

    int count(String key, long nowMs, long windowMs);

    void remove(String key, String token);

    void delete(String key);

    void clear();

    /** Keys with that prefix that still have a hit inside the window. */
    int size(String prefix, long nowMs, long windowMs);
}
