// Fabric mod for EssentialsX-style aliases with validation, feedback, and tab completion
package com.mogli42.essxaliases;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

import static net.minecraft.server.command.CommandManager.*;

public class EssentialsXAliasesMod implements ModInitializer {
    private static final Map<String, AliasEntry> aliasMap = new LinkedHashMap<>();
    private static final File CONFIG_FILE = new File("config/essx_aliases.json");
    private static final File LOG_FILE = new File("logs/essxaliases.log");
    private static LuckPerms luckPerms;
    private static MinecraftServer server;

    private static final Map<String, String> permissionMap = Map.ofEntries(
        Map.entry("tpa", "essentialcommands.tpa"),
        Map.entry("tpahere", "essentialcommands.tpahere"),
        Map.entry("tpaccept", "essentialcommands.tpaccept"),
        Map.entry("tpdeny", "essentialcommands.tpdeny"),
        Map.entry("home set", "essentialcommands.home.set"),
        Map.entry("home tp", "essentialcommands.home.tp"),
        Map.entry("home delete", "essentialcommands.home.delete"),
        Map.entry("home list", "essentialcommands.home.tp"),
        Map.entry("home tp_other", "essentialcommands.home_tp_others"),
        Map.entry("home tp_offline", "essentialcommands.home_tp_others"),
        Map.entry("warp set", "essentialcommands.warp.set"),
        Map.entry("warp tp", "essentialcommands.warp.tp"),
        Map.entry("warp delete", "essentialcommands.warp.delete"),
        Map.entry("spawn", "essentialcommands.spawn"),
        Map.entry("spawn set", "essentialcommands.spawn.set"),
        Map.entry("back", "essentialcommands.back"),
        Map.entry("nickname set", "essentialcommands.nickname.set"),
        Map.entry("nickname clear", "essentialcommands.nickname.clear"),
        Map.entry("nickname reveal", "essentialcommands.nickname.reveal"),
        Map.entry("randomteleport", "essentialcommands.randomteleport"),
        Map.entry("rtp", "essentialcommands.rtp"),
        Map.entry("fly", "essentialcommands.fly"),
        Map.entry("fly other", "essentialcommands.fly"),
        Map.entry("workbench", "essentialcommands.workbench"),
        Map.entry("enderchest", "essentialcommands.enderchest"),
        Map.entry("config reload", "essentialcommands.config.reload")
    );

    private static class AliasEntry {
        String target;
        String permission;
    }

    @Override
    public void onInitialize() {
        luckPerms = LuckPermsProvider.get();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            server = dispatcher.getServer();
            loadAliases();
            registerAliases(dispatcher);
            registerAliasCommand(dispatcher);
        });
    }

    private synchronized void loadAliases() {
        aliasMap.clear();
        try {
            if (!CONFIG_FILE.exists()) {
                Files.createDirectories(CONFIG_FILE.getParentFile().toPath());
                JsonObject defaultJson = new JsonObject();
                JsonObject aliases = new JsonObject();
                JsonObject sethome = new JsonObject();
                sethome.addProperty("target", "home set {home}");
                aliases.add("sethome {home}".toLowerCase(), sethome);
                defaultJson.add("aliases", aliases);
                try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(defaultJson, writer);
                }
            }
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject aliases = json.getAsJsonObject("aliases");
                for (Map.Entry<String, JsonElement> entry : aliases.entrySet()) {
                    String key = entry.getKey().toLowerCase();
                    if (aliasMap.containsKey(key)) continue;
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    AliasEntry alias = new AliasEntry();
                    alias.target = obj.get("target").getAsString();
                    alias.permission = obj.has("permission") ? obj.get("permission").getAsString() : inferPermission(alias.target);

                    Set<String> definedArgs = new HashSet<>();
                    for (String p : key.split(" ")) {
                        if (p.startsWith("{") && p.endsWith("}")) {
                            definedArgs.add(p.substring(1, p.length() - 1));
                        }
                    }
                    for (String token : alias.target.split(" ")) {
                        if (token.startsWith("{") && token.endsWith("}")) {
                            String arg = token.substring(1, token.length() - 1);
                            if (!definedArgs.contains(arg)) {
                                System.err.println("[EssXAliases] Warning: Placeholder {" + arg + "} in target not defined in alias key: " + key);
                            }
                        }
                    }

                    aliasMap.put(key, alias);
                }
            }
        } catch (Exception e) {
            logError("Failed to load aliases", e);
        }
    }

    private synchronized void saveAliases() {
        try {
            JsonObject json = new JsonObject();
            JsonObject aliases = new JsonObject();
            for (Map.Entry<String, AliasEntry> entry : aliasMap.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("target", entry.getValue().target);
                if (entry.getValue().permission != null) {
                    obj.addProperty("permission", entry.getValue().permission);
                }
                aliases.add(entry.getKey(), obj);
            }
            json.add("aliases", aliases);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(CONFIG_FILE))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
            }
        } catch (Exception e) {
            logError("Failed to save aliases", e);
        }
    }

    private void logError(String msg, Exception e) {
        System.err.println("[EssXAliases] " + msg);
        e.printStackTrace();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            writer.write("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "] " + msg + "\n");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            writer.write(sw.toString());
        } catch (IOException ignored) {}
    }

    private void registerAliases(CommandDispatcher<ServerCommandSource> dispatcher) {
        for (Map.Entry<String, AliasEntry> entry : aliasMap.entrySet()) {
            String[] parts = entry.getKey().split(" ");
            dispatcher.register(buildAlias(parts, 0, entry.getValue().target, entry.getValue().permission));
        }
    }

    private LiteralArgumentBuilder<ServerCommandSource> buildAlias(String[] parts, int index, String target, String permission) {
        String part = parts[index];
        boolean isArg = part.startsWith("{") && part.endsWith("}");
        boolean isLast = index == parts.length - 1;

        if (isLast) {
            String argName = isArg ? part.substring(1, part.length() - 1) : null;
            return isArg
                ? argument(argName, StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> server != null ? CommandSource.suggestMatching(server.getPlayerNames(), builder) : builder.buildFuture())
                    .executes(ctx -> {
                        Map<String, String> values = collectArgs(ctx, parts);
                        return executeAlias(ctx.getSource(), target, values, permission);
                    })
                : literal(part).executes(ctx -> executeAlias(ctx.getSource(), target, Collections.emptyMap(), permission));
        } else {
            if (isArg) {
                String argName = part.substring(1, part.length() - 1);
                return argument(argName, StringArgumentType.word())
                    .suggests((ctx, builder) -> server != null ? CommandSource.suggestMatching(server.getPlayerNames(), builder) : builder.buildFuture())
                    .then(buildAlias(parts, index + 1, target, permission));
            } else {
                return literal(part).then(buildAlias(parts, index + 1, target, permission));
            }
        }
    }

    private Map<String, String> collectArgs(CommandContext<ServerCommandSource> ctx, String[] parts) {
        Map<String, String> values = new HashMap<>();
        for (String part : parts) {
            if (part.startsWith("{") && part.endsWith("}")) {
                String key = part.substring(1, part.length() - 1);
                values.put(key, StringArgumentType.getString(ctx, key));
            }
        }
        return values;
    }

    private static int executeAlias(ServerCommandSource source, String commandTemplate, Map<String, String> args, String permission) {
        try {
            if (permission != null && source.getEntity() instanceof ServerPlayerEntity player) {
                User user = luckPerms.getUserManager().getUser(player.getUuid());
                if (user == null) {
                    source.sendFeedback(Text.literal("Could not fetch your user permissions."), false);
                    return 0;
                }
                QueryOptions options = luckPerms.getContextManager().getQueryOptions(user)
                    .orElse(luckPerms.getContextManager().getStaticQueryOptions());
                if (!user.getCachedData().getPermissionData(options).checkPermission(permission).asBoolean()) {
                    source.sendFeedback(Text.literal("You do not have permission to use this alias."), false);
                    return 0;
                }
            }

            String finalCommand = commandTemplate;
            for (Map.Entry<String, String> entry : args.entrySet()) {
                finalCommand = finalCommand.replace("{" + entry.getKey() + "}", entry.getValue());
            }

            int result = server.getCommandManager().executeWithPrefix(source, finalCommand);
            if (result == 0) {
                source.sendFeedback(Text.literal("Alias executed but returned no result. Check command or permissions."), false);
            }
        } catch (Exception e) {
            logError("Alias execution failed", e);
            source.sendFeedback(Text.literal("Alias execution failed: " + e.getMessage()), false);
            return 0;
        }
        return 1;
    }

    private void registerAliasCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        SuggestionProvider<ServerCommandSource> aliasNames = (ctx, builder) ->
            CommandSource.suggestMatching(aliasMap.keySet(), builder);

        dispatcher.register(literal("essxalias")
            .requires(source -> source.hasPermissionLevel(4) || (source.getEntity() instanceof ServerPlayerEntity player &&
                    luckPerms.getUserManager().getUser(player.getUuid())
                        .getCachedData()
                        .getPermissionData(luckPerms.getContextManager().getStaticQueryOptions())
                        .checkPermission("essxaliases.admin").asBoolean()
            ))
            .then(literal("add")
                .then(argument("name", StringArgumentType.word())
                    .then(argument("command", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                            String command = StringArgumentType.getString(ctx, "command");

                            if (aliasMap.containsKey(name)) {
                                ctx.getSource().sendFeedback(Text.literal("§cAlias /" + name + " already exists. Remove it first."), false);
                                return 0;
                            }

                            for (String key : aliasMap.keySet()) {
                                if (command.toLowerCase().startsWith(key + " ")) {
                                    ctx.getSource().sendFeedback(Text.literal("§cCannot create alias to another alias (no chaining)."), false);
                                    return 0;
                                }
                            }

                            ParseResults<ServerCommandSource> parse = server.getCommandManager().getDispatcher().parse(command, ctx.getSource());
                            if (!parse.getExceptions().isEmpty()) {
                                ctx.getSource().sendFeedback(Text.literal("§cWarning: Target command has parse errors."), false);
                            }

                            AliasEntry entry = new AliasEntry();
                            entry.target = command;
                            entry.permission = inferPermission(command);

                            aliasMap.put(name, entry);
                            saveAliases();

                            ctx.getSource().sendFeedback(Text.literal("Alias /" + name + " → /" + command + " added."), false);
                            return 1;
                        })
                    )
                )
            )
            .then(literal("remove")
                .then(argument("name", StringArgumentType.word()).suggests(aliasNames)
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                        if (aliasMap.remove(name) != null) {
                            saveAliases();
                            ctx.getSource().sendFeedback(Text.literal("Alias /" + name + " removed."), false);
                        } else {
                            ctx.getSource().sendFeedback(Text.literal("Alias /" + name + " not found."), false);
                        }
                        return 1;
                    })
                )
            )
            .then(literal("reload")
                .executes(ctx -> {
                    loadAliases();
                    ctx.getSource().sendFeedback(Text.literal("EssXAliases reloaded from config."), false);
                    return 1;
                })
            )
            .then(literal("list")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("§6EssXAliases - Registered Aliases:"), false);
                    for (Map.Entry<String, AliasEntry> e : aliasMap.entrySet()) {
                        String msg = "§e/" + e.getKey() + " §7→ /" + e.getValue().target;
                        if (e.getValue().permission != null) {
                            msg += " §8[" + e.getValue().permission + "]";
                        }
                        ctx.getSource().sendFeedback(Text.literal(msg), false);
                    }
                    return 1;
                })
            )
            .then(literal("help")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("§6EssXAliases Help:"), false);
                    ctx.getSource().sendFeedback(Text.literal("§e/essxalias help §7- Show this help menu"), false);
                    ctx.getSource().sendFeedback(Text.literal("§e/essxalias list §7- List all registered aliases"), false);
                    ctx.getSource().sendFeedback(Text.literal("§e/essxalias reload §7- Reload aliases from config"), false);
                    ctx.getSource().sendFeedback(Text.literal("§e/essxalias add <alias> <command> §7- Add new alias"), false);
                    ctx.getSource().sendFeedback(Text.literal("§e/essxalias remove <alias> §7- Remove an alias"), false);
                    return 1;
                })
            )
        );
    }

    private String inferPermission(String command) {
        String[] parts = command.split(" ");
        for (int i = parts.length; i > 0; i--) {
            String key = String.join(" ", Arrays.copyOfRange(parts, 0, i));
            if (permissionMap.containsKey(key)) {
                return permissionMap.get(key);
            }
        }
        return null;
    }

    public static Set<String> getAliasKeys() {
        return aliasMap.keySet();
    }
}
