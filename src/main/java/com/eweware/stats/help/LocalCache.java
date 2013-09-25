package com.eweware.stats.help;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.statistics.StatisticsGateway;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

import java.io.Serializable;

/**
 * @author rk@post.harvard.edu
 *         Date: 7/7/13 Time: 3:55 PM
 */
public class LocalCache<K, V extends Serializable> {

    private static final CacheManager _cacheManager = CacheManager.create();
    private static int _cacheCount = 1;
    private Cache _cache;
    private int _timeToLive = 0;
    private int _timeToIdle = 0;

    public LocalCache(String name, Integer entries, Integer timeToLiveInSeconds, Integer timeToIdleInSeconds) {
        name += _cacheCount++;
        _timeToLive = timeToLiveInSeconds;
        _timeToIdle = timeToIdleInSeconds;
        final CacheConfiguration config = new CacheConfiguration(name, entries)
//                .eternal(false)
//                .timeToLiveSeconds(timeToLiveInSeconds)
//                .timeToIdleSeconds(timeToIdleInSeconds)
                .memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LRU)
                .persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE))
                .overflowToOffHeap(false);
        _cache = new Cache(config);
        _cacheManager.addCache(_cache);
    }

    public LocalCache(String name, Integer entries) {
        this(name, entries, 60, 30);
    }

    public V get(K key) {
        final Element e = _cache.get(key);
        return (e == null) ? null : (V) e.getObjectValue();
    }

    public void put(K key, V value) {
        _cache.put(new Element(key, value, _timeToLive, _timeToIdle));
    }

    public String getStatistics() {
        final StatisticsGateway statistics = _cache.getStatistics();
        final StringBuilder b = new StringBuilder();
        b.append("Hit Ratio=");
        b.append(statistics.cacheHitRatio());
        b.append("\nHits=");
        b.append(statistics.cacheHitCount());
        b.append("\nMisses=");
        b.append(statistics.cacheMissCount());
        b.append("\nEvicted=");
        b.append(statistics.cacheEvictedCount());
        b.append("\nHeap=");
        b.append(statistics.getLocalHeapSizeInBytes());
        b.append("\n");
        return b.toString();
    }

    public void evict() {
        _cache.evictExpiredElements();
    }
}

