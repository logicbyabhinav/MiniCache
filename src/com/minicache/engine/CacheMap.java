package com.minicache.engine;

public class CacheMap {

    static class Store {

        String key = null;
        CacheObject data = null;
        Store next = null;

        public Store(String key, CacheObject value) {
            this(key, value, null);
        }

        public Store(String key, CacheObject value, Store next) {
            this.key = key;
            this.data = value;
            this.next = next;
        }
    }

    private Store[] store;
    private int buckets = 16; //default
    private int currentSize = 0;

    public CacheMap() {
        this(16);
    }

    public CacheMap(int buckets) {
        this.buckets = buckets;
        this.store = new Store[buckets];
    }

    private int getIndex(String key) {
        return Math.abs(key.hashCode()) % buckets;
    }

    public boolean put(String key, CacheObject value) {
        int index = getIndex(key);
        Store Node = new Store(key, value);
        Store current = store[index];
//true means normal store operation, false means overwrite
        if (current == null) {
            store[index] = Node;
            currentSize++;
            return true;
        }

        while (current != null) {
            if (current.key.equals(key)) {
                current.data = value;
                return false;
            }

            if (current.next == null) {
                current.next = Node;
                currentSize++;
                return true;
            }
            current = current.next;
        }
        return false;
    }

    public CacheObject get(String key) {
        int index = getIndex(key);
        Store current = store[index];

        if (current == null) {
            return null;
        }

        while (current != null) {
            if (current.key.equals(key)) {
                return current.data;
            } else {
                current = current.next; // Not this one, move to the next book
            }

        }
        return null;
    }

    public CacheObject remove(String key) {
        int index = getIndex(key);
        Store current = store[index];
        Store previous = store[index];
        CacheObject returnable = null;

        if (current == null) {
            return null;
        }

        while (current != null) {// using two chasing pointer method
            if (current.key.equals(key)) {
                if (current == previous) { //if its the head
                    returnable = current.data;
                    store[index] = current.next;
                    currentSize--;
                    break;
                }
                if (current != previous) {
                    returnable = current.data;
                    previous.next = current.next;
                    currentSize--;
                    break;
                }

            }
            previous = current;
            current = current.next;
        }
        return returnable;
    }

    public boolean exists(String key) {
        int index = getIndex(key);
        Store current = store[index];
        while (current != null) {
            if (current.key.equals(key)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    public int size() {
        return currentSize;
    }

    public void clear() {
        for (int i = 0; i < buckets; i++) {
            store[i] = null;
        }
        currentSize = 0;
    }
}
