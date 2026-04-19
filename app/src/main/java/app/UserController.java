package app;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@GateController
public class UserController {

    record User(int id, String name, String email) {}

    private final CopyOnWriteArrayList<User> users = new CopyOnWriteArrayList<>(List.of(
        new User(1, "Alice", "alice@example.com"),
        new User(2, "Bob",   "bob@example.com")
    ));

    private final AtomicInteger idGenerator = new AtomicInteger(2);

    @GetMapping("/users")
    public void getUsers(Context ctx) {
        ctx.json(users);
    }

    @GetMapping("/user")
    public void getUser(Context ctx) {
        String idParam = ctx.query("id");
        if (idParam == null) {
            ctx.status(400);
            ctx.json(Map.of("error", "id is required"));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(idParam);
        } catch (NumberFormatException e) {
            ctx.status(400);
            ctx.json(Map.of("error", "id must be a number"));
            return;
        }

        users.stream()
            .filter(u -> u.id() == id)
            .findFirst()
            .ifPresentOrElse(
                ctx::json,
                () -> {
                    ctx.status(404);
                    ctx.json(Map.of("error", "user not found"));
                }
            );
    }

    @PostMapping("/users")
    public void createUser(Context ctx) {
        try {
            CreateUserRequest req = ctx.bodyAs(CreateUserRequest.class);
            if (req == null || req.name() == null || req.email() == null) {
                ctx.status(400);
                ctx.json(Map.of("error", "name and email are required"));
                return;
            }
            User user = new User(idGenerator.incrementAndGet(), req.name(), req.email());
            users.add(user);
            ctx.status(201);
            ctx.json(user);
        } catch (Exception e) {
            ctx.status(400);
            ctx.json(Map.of("error", "Invalid request body"));
        }
    }

    record CreateUserRequest(String name, String email) {}
}
