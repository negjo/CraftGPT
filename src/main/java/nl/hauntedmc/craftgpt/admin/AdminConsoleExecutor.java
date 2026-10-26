package nl.hauntedmc.craftgpt.admin;

import org.bukkit.command.ConsoleCommandSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;

public final class AdminConsoleExecutor {
    private AdminConsoleExecutor() { }
    public static String execute(ConsoleCommandSender real, BiFunction<ConsoleCommandSender,String,Boolean> dispatch, String command) {
        var output = new StringBuilder();
        ConsoleCommandSender capture = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class}, (proxy,method,args) -> {
            if (method.getName().equals("sendMessage") && args != null) {
                synchronized (output) {
                    for (Object value : args) {
                        if (value instanceof String text) append(output,text);
                        else if (value instanceof String[] texts) for (String text : texts) append(output,text);
                        else if (value instanceof Component component) append(output,PlainTextComponentSerializer.plainText().serialize(component));
                    }
                }
                return null;
            }
            try { return method.invoke(real,args); }
            catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        boolean dispatched = dispatch.apply(capture,command);
        synchronized (output) { return AdminFileTools.redact("dispatched="+dispatched+"\n"+output); }
    }
    private static void append(StringBuilder output,String text) {
        int available = 16000 - output.length();
        if (available > 0) output.append(text,0,Math.min(text.length(),available)).append('\n');
    }
}