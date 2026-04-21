package app;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.DeleteMapping;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;
import dev.gate.mapping.PutMapping;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@GateController
public class UserController {

    record User(int id, String name, String email) {}

    private final ConcurrentHashMap<Integer, User> users = new ConcurrentHashMap<>(Map.of(
        1, new User(1, "Alice", "alice@example.com"),
        2, new User(2, "Bob",   "bob@example.com")
    ));

    private final AtomicInteger idGenerator = new AtomicInteger(2);

    @GetMapping("/users")
    public void getUsers(Context ctx) {
        ctx.json(users.values());
    }

    @GetMapping("/users/{id}")
    public void getUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

        User user = users.get(id);
        if (user != null) {
            ctx.json(user);
        } else {
            ctx.status(404).json(Map.of("error", "user not found"));
        }
    }

    @PostMapping("/users")
    public void createUser(Context ctx) {
        try {
            CreateUserRequest req = ctx.bodyAs(CreateUserRequest.class);
            if (req == null || req.name() == null || req.email() == null) {
                ctx.status(400).json(Map.of("error", "name and email are required"));
                return;
            }
            int newId = idGenerator.incrementAndGet();
            User user = new User(newId, req.name(), req.email());
            users.put(newId, user);
            ctx.status(201).json(user);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
        }
    }

    @PutMapping("/users/{id}")
    public void updateUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

        try {
            CreateUserRequest req = ctx.bodyAs(CreateUserRequest.class);
            if (req == null || req.name() == null || req.email() == null) {
                ctx.status(400).json(Map.of("error", "name and email are required"));
                return;
            }
            User updated = new User(id, req.name(), req.email());
            User prev = users.computeIfPresent(id, (k, v) -> updated);
            if (prev == null) {
                ctx.status(404).json(Map.of("error", "user not found"));
                return;
            }
            ctx.json(updated);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
        }
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(Context ctx) {
        int id = parseId(ctx);
        if (id < 0) return;

        if (users.remove(id) != null) {
            ctx.status(204).result("");
        } else {
            ctx.status(404).json(Map.of("error", "user not found"));
        }
    }

    private int parseId(Context ctx) {
        try {
            return Integer.parseInt(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "id must be a number"));
            return -1;
        }
    }

    record CreateUserRequest(String name, String email) {}
}
