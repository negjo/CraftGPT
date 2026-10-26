package nl.hauntedmc.craftgpt.admin;

public final class AdminConsolePolicy {
    private AdminConsolePolicy() { }

    public static String validate(String command) {
        if (command == null || command.isBlank() || command.length() > 2048 || command.chars().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalArgumentException("Console command must be one nonempty line of at most 2048 characters.");
        }
        String cleaned = command.trim();
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank()) throw new IllegalArgumentException("Console command must contain a command name.");
        return cleaned;
    }
}