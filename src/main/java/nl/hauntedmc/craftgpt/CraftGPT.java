package nl.hauntedmc.craftgpt;

import nl.hauntedmc.craftgpt.cmd.CraftGPTCommand;
import nl.hauntedmc.craftgpt.admin.AdminCoordinator;
import nl.hauntedmc.craftgpt.generation.GenerationCoordinator;
import nl.hauntedmc.craftgpt.util.MessageBundle;
import nl.hauntedmc.craftgpt.util.PluginConfig;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class CraftGPT extends JavaPlugin {
    private PluginConfig pluginConfig;
    private MessageBundle messageBundle;
    private GenerationCoordinator generationCoordinator;
    private AdminCoordinator adminCoordinator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        messageBundle = loadMessageBundle();
        pluginConfig = PluginConfig.load(getConfig());
        generationCoordinator = new GenerationCoordinator(this);
        getServer().getPluginManager().registerEvents(generationCoordinator, this);
        if (getConfig().getBoolean("admin.enabled", true)) {
            try {
                adminCoordinator = new AdminCoordinator(this);
                getServer().getPluginManager().registerEvents(adminCoordinator, this);
            } catch (IOException e) { getLogger().warning("Admin mode unavailable: " + e.getMessage()); }
        }
        registerCommands();
    }

    @Override
    public void onDisable() {
        if (adminCoordinator != null) adminCoordinator.shutdown();
        if (generationCoordinator != null) {
            generationCoordinator.shutdown();
        }
    }

    private void registerCommands() {
        CraftGPTCommand commandExecutor = new CraftGPTCommand(generationCoordinator, adminCoordinator);
        PluginCommand command = getCommand("craftgpt");
        if (command == null) {
            throw new IllegalStateException("Required commands are missing from plugin.yml.");
        }
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
    }

    public static CraftGPT getInstance() {
        return JavaPlugin.getPlugin(CraftGPT.class);
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public MessageBundle getMessageBundle() {
        return messageBundle;
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        pluginConfig = PluginConfig.load(getConfig());
        messageBundle = loadMessageBundle();
    }

    private MessageBundle loadMessageBundle() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder for messages.yml.");
        }
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().pathSeparator('/');
        try {
            configuration.load(messagesFile);
            // New message keys must work with existing customized messages.yml files.
            try (var stream = getResource("messages.yml")) {
                if (stream != null) {
                    YamlConfiguration defaults = new YamlConfiguration();
                    defaults.options().pathSeparator('/');
                    defaults.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                    configuration.setDefaults(defaults);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("Could not load messages.yml: " + e.getMessage());
        }
        return new MessageBundle(configuration);
    }
}
