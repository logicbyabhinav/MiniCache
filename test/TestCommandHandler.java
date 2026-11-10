package test;

import com.minicache.engine.CacheEngine;
import com.minicache.command.CommandHandler;

public class TestCommandHandler {

    public static void main(String[] args) throws Exception {
        CacheEngine engine = new CacheEngine(10);
        CommandHandler handler = new CommandHandler(engine);

        // 1. PING tests
        assert handler.handle("PING").equals("+PONG\r\n") : "PING failed";
        assert handler.handle("ping").equals("+PONG\r\n") : "Case insensitive ping failed";
        assert handler.handle("PING hello").equals("$5\r\nhello\r\n") : "PING with argument failed";
        assert handler.handle("PING too many args").equals("-ERR wrong number of arguments for 'ping' command\r\n") : "PING arity failed";

        // 2. SET tests
        assert handler.handle("SET user logicbyabhinav").equals("+OK\r\n") : "SET failed";
        assert handler.handle("SET").equals("-ERR wrong number of arguments for 'set' command\r\n") : "SET arity failed";
        assert handler.handle("SET key val EXTRA").equals("-ERR wrong number of arguments for 'set' command\r\n") : "SET arity failed";
        assert handler.handle("SET key val FOO 10").equals("-ERR syntax error\r\n") : "SET syntax error failed";
        assert handler.handle("SET key val EX notanumber").equals("-ERR value is not an integer or out of range\r\n") : "SET NaN failed";
        assert handler.handle("SET key val EX -5").equals("-ERR value is not an integer or out of range\r\n") : "SET negative TTL failed";
        assert handler.handle("SET key val EX 0").equals("-ERR value is not an integer or out of range\r\n") : "SET zero TTL failed";

        // 3. GET tests
        assert handler.handle("GET user").equals("$14\r\nlogicbyabhinav\r\n") : "GET failed";
        assert handler.handle("GET nonexisting").equals("$-1\r\n") : "GET miss failed";
        assert handler.handle("GET").equals("-ERR wrong number of arguments for 'get' command\r\n") : "GET arity failed";

        // 4. EXISTS tests
        assert handler.handle("EXISTS user").equals(":1\r\n") : "EXISTS 1 failed";
        assert handler.handle("EXISTS nonexisting").equals(":0\r\n") : "EXISTS 0 failed";

        // 5. DEL tests
        assert handler.handle("DEL user").equals(":1\r\n") : "DEL 1 failed";
        assert handler.handle("DEL user").equals(":0\r\n") : "DEL 0 failed";
        assert handler.handle("EXISTS user").equals(":0\r\n") : "EXISTS after DEL failed";
        assert handler.handle("GET user").equals("$-1\r\n") : "GET after DEL failed";

        // 6. TTL Expiration via CommandHandler
        assert handler.handle("SET sessionToken abcdef EX 1").equals("+OK\r\n") : "SET EX failed";
        assert handler.handle("GET sessionToken").equals("$6\r\nabcdef\r\n") : "Immediate GET failed";
        Thread.sleep(1100);
        assert handler.handle("GET sessionToken").equals("$-1\r\n") : "Expired GET should return null bulk string";
        assert handler.handle("EXISTS sessionToken").equals(":0\r\n") : "Expired EXISTS should return 0";

        // 7. Unknown command
        assert handler.handle("FOOBAR test").equals("-ERR unknown command 'foobar'\r\n") : "Unknown command failed";

        System.out.println("ALL_COMMAND_HANDLER_TESTS_PASSED_100_PERCENT");
    }
}
