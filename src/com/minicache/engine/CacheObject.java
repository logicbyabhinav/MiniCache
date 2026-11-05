package com.minicache.engine;

class InvalidCacheObjectException extends RuntimeException {

    public InvalidCacheObjectException(String message) {
        super(message);
    }
}

public class CacheObject {

    protected final String key;
    protected String value;
    protected final long expiresAt;
    protected long lastAccessedAt;

    public CacheObject(String key, String value) {
        this(key, value, -1L);
    }

    public CacheObject(String key, String value, long ttlSeconds) throws InvalidCacheObjectException {
        if (key == null || key.trim().isEmpty()) {
            throw new InvalidCacheObjectException("Key cannot be null or empty");
        } else {
            this.key = key;
            this.value = value;

            final long currentTime = System.currentTimeMillis();

            if (ttlSeconds > 0) {
                this.expiresAt = currentTime + (ttlSeconds * 1000L);
            } else {
                this.expiresAt = -1L;
            }

            this.lastAccessedAt = currentTime;
        }
    }

    public boolean isExpired() {
        return !(System.currentTimeMillis() <= this.expiresAt || this.expiresAt == -1L);
    }

    public long touch() {
        this.lastAccessedAt = System.currentTimeMillis();
        return this.lastAccessedAt;
    }

    public String getKey() {
        return this.key;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
        getValue();
    }

    public long getExpiresAt() {
        return this.expiresAt;
    }

    public long getLastAccessedAt() {
        return this.lastAccessedAt;
    }
}
