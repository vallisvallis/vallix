package com.alphay.boot.web.controller.vallix;




import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class KlineCacheService {
    
    private static final long CACHE_EXPIRE_TIME = 5 * 60 * 1000;
    
    @Data
    private static class CacheEntry {
        private Map<String, Object> data;
        private long timestamp;
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }
    
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    
    public Map<String, Object> get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("缓存命中: {}", key);
            return entry.getData();
        }
        
        if (entry != null) {
            cache.remove(key);
            log.debug("缓存过期: {}", key);
        }
        
        return null;
    }
    
    public void put(String key, Map<String, Object> data) {
        CacheEntry entry = new CacheEntry();
        entry.setData(data);
        entry.setTimestamp(System.currentTimeMillis());
        cache.put(key, entry);
        log.debug("缓存设置: {}", key);
    }
    
    public void clear() {
        cache.clear();
        log.info("缓存已清空");
    }
}
