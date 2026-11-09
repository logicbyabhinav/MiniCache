package com.minicache.command;

import com.minicache.engine.CacheEngine;

public class CommandHandler {

    private final CacheEngine engine;

    public CommandHandler(CacheEngine engine) {
        this.engine = engine;
    }

    public String handle(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return "-ERR empty command\r\n";
        }

        String[] command = rawCommand.trim().split("\\s+");

        String method = command[0].toUpperCase();//SET, GET, DEL, EXISTS

        return switch (method) {
            case "PING" ->
                handlePing(command);
            case "SET" ->
                handleSet(command);
            case "GET" ->
                handleGet(command);
            case "DEL" ->
                handleDel(command);
            case "EXISTS" ->
                handleExists(command);
            default ->
                "-ERR unknown command '" + method.toLowerCase() + "'\r\n";
        };

    }

    private String handlePing(String[] tokens) {
        if (tokens.length > 2) {
            return "-ERR wrong number of arguments for 'ping' command\r\n";
        }
        if (tokens.length == 1) {
            return "+PONG\r\n";
        }
        String msg = tokens[1];
        return "$" + msg.length() + "\r\n" + msg + "\r\n";
    }

    private String handleSet(String[] tokens) {
        if (tokens.length != 3 && tokens.length != 5) {
            return "-ERR wrong number of arguments for 'set' command\r\n";
        }

        if (tokens.length == 3) {
            engine.set(tokens[1], tokens[2]);
            return "+OK\r\n";
        }

        if (!tokens[3].equalsIgnoreCase("EX")) {
            return "-ERR syntax error\r\n";
        }

        try {
            long ttl = Long.parseLong(tokens[4]);
            if (ttl <= 0) {
                return "-ERR value is not an integer or out of range\r\n";
            }
            engine.set(tokens[1], tokens[2], ttl);
            return "+OK\r\n";
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
    }

    private String handleGet(String[] tokens) {
        if (tokens.length != 2) {
            return "-ERR wrong number of arguments for 'get' command\r\n";
        }
        String value = engine.get(tokens[1]);
        if (value == null) {
            return "$-1\r\n";
        }
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }

    private String handleDel(String[] tokens) {
        if (tokens.length != 2) {
            return "-ERR wrong number of arguments for 'del' command\r\n";
        }
        boolean deleted = engine.delete(tokens[1]);
        return deleted ? ":1\r\n" : ":0\r\n";
    }

    private String handleExists(String[] tokens) {
        if (tokens.length != 2) {
            return "-ERR wrong number of arguments for 'exists' command\r\n";
        }
        boolean exists = engine.exists(tokens[1]);
        return exists ? ":1\r\n" : ":0\r\n";
    }

}
