package com.minicache.engine;

public class CacheEngine {

    static class Node {

        String key;
        Node prev;
        Node next;

        public Node(String key) {
            this.key = key;
        }
    }

    private final CacheMap storage;
    private int maxCapacity;

    private final Node head = new Node(null);//internal LRU linkedlist head anchor
    private final Node tail = new Node(null);//internal LRU linkedlist tail anchor

    public CacheEngine() {
        this(100, 16); //default
    }

    public CacheEngine(int capacity) {
        this(capacity, 16);
    }

    public CacheEngine(int capacity, int buckets) {
        this.maxCapacity = capacity;
        this.storage = new CacheMap(buckets);
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    private void addToHead(Node node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node node) {
        node.next.prev = node.prev;
        node.prev.next = node.next;
    }

    private void moveToHead(Node node) {
        removeNode(node);
        addToHead(node);
    }

    public void set(String key, String value) {
        set(key, value, -1L);
    }

    private boolean eviction() {
        Node oldest = tail.prev;
        if(oldest == head){
            return false;
        }
        storage.remove(oldest.key);
        removeNode(oldest);
        return true;
    }

    public void set(String key, String value, long ttlSeconds) {
        Node node = new Node(key);
        CacheObject data = new CacheObject(key, value, ttlSeconds, node);
        
        if (storage.exists(key)) {
            CacheObject old = storage.get(key);
            Node oldNode =(Node)old.getLruNode();
            removeNode(oldNode);
            storage.put(key, data);
            addToHead(node);
        } 
        
        else {
            if (storage.size() >= maxCapacity) {
                if (!eviction()) {
                    return;
                }
            }
            storage.put(key, data);
            addToHead(node);
        }
    }

    public String get(String key) {
        CacheObject data = storage.get(key);
        if (data != null) {
            Node link = (Node) data.getLruNode();
            if (!data.isExpired()) {
                data.touch();
                moveToHead(link);
                return data.value;
            }
            storage.remove(key);
            removeNode(link);
            return null; //Cache miss, expired data
        }
        return null; // Cache miss, data not found
    }

        public boolean delete(String key) {
        CacheObject removed = storage.remove(key);
        if (removed != null) {
            removeNode((Node) removed.getLruNode());
            return true;
        }
        return false;
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    public int size() {
        return storage.size();
    }

    public void clear() {
        storage.clear();
        head.next = tail;
        tail.prev = head;
    }

}
