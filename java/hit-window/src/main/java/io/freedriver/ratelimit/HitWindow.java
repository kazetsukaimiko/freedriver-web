package io.freedriver.ratelimit;

/**
 * A sliding window of hits per key. The window's clock is the Redis server's {@code TIME}, so every
 * replica reads the same window. A Redis restart may drop the keys.
 */
public interface HitWindow {

    /** A token for this hit, or null when the window already holds {@code max} hits. */
    String tryAdd(String key, long windowMs, int max);

    /** Records a hit even when the window is already full. */
    String add(String key, long windowMs);

    /** Hits inside the window. */
    int count(String key, long windowMs);

    /** Gives back the hit that {@code token} names. */
    void remove(String key, String token);

    /** Drops every hit for the key. */
    void delete(String key);
}
