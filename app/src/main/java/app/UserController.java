package app;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PostMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@GateController
public class UserController {

    record User(int id, String name, String email) {}

    private final List<User> users = new ArrayList<>(List.of(
        new User(1, "Alice", "alice@example.com"),
        new User(2, "Bob",   "bob@example.com")
    ));

    @GetMapping("/users")
    public void getUsers(Context ctx) {
        ctx.json(users);
    }

    @GetMapping("/user")
    public void getUser(Context ctx) {
        String idParam = ctx.query("id");
        if (idParam == null) {
            ctx.json(Map.of("error", "id is required"));
            return;
        }

        int id = Integer.parseInt(idParam);
        users.stream()
            .filter(u -> u.id() == id)
            .findFirst()
            .ifPresentOrElse(
                ctx::json,
                () -> ctx.json(Map.of("error", "user not found"))
            );
    }

    @PostMapping("/users")
    public void createUser(Context ctx) {
        String body = ctx.body();
        int newId = users.size() + 1;
        User user = new User(newId, "User" + newId, "user" + newId + "@example.com");
        users.add(user);
        ctx.json(user);
    }
}
