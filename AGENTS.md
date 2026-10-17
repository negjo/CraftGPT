# Server lifecycle preference

The user controls when Minecraft servers run and needs access to their console.
Do not automatically start or restart either Minecraft server, including for tests
or after configuration/plugin changes. Leave launching to the user through the
normal `.bat` launcher, which opens their interactive console. Only start a server
if the user explicitly asks the agent to do so. Stop servers cleanly when requested,
allowing world/player saves to complete. Do not launch hidden server consoles.
