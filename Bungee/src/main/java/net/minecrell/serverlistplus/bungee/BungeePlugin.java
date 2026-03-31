/*
 * ServerListPlus - https://git.io/slp
 * Copyright (C) 2014 Minecrell (https://github.com/Minecrell)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.bungee;

import static net.minecrell.serverlistplus.core.logging.JavaServerListPlusLogger.DEBUG;
import static net.minecrell.serverlistplus.core.logging.JavaServerListPlusLogger.ERROR;
import static net.minecrell.serverlistplus.core.logging.JavaServerListPlusLogger.INFO;

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilderSpec;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import net.minecrell.serverlistplus.bungee.integration.BungeeBanBanProvider;
import net.minecrell.serverlistplus.bungee.config.BungeeConf;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.ServerListPlusException;
import net.minecrell.serverlistplus.core.config.PassthroughConf;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.config.storage.InstanceStorage;
import net.minecrell.serverlistplus.core.favicon.FaviconCache;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.logging.JavaServerListPlusLogger;
import net.minecrell.serverlistplus.core.logging.ServerListPlusLogger;
import net.minecrell.serverlistplus.core.player.ban.integration.AdvancedBanBanProvider;
import net.minecrell.serverlistplus.core.plugin.MaxPlayersProvider;
import net.minecrell.serverlistplus.core.plugin.ScheduledTask;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.replacement.rgb.RGBFormat;
import net.minecrell.serverlistplus.core.status.ResponseFetcher;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusRequest;
import net.minecrell.serverlistplus.core.status.StatusResponse;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.Randoms;
import net.minecrell.serverlistplus.core.util.UUIDs;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BungeePlugin extends BungeePluginBase implements ServerListPlusPlugin, MaxPlayersProvider {
    private ServerListPlusCore core;
    private RGBFormat rgbFormat = RGBFormat.UNSUPPORTED;
    private Listener connectionListener, pingListener;

    private FaviconCache<Favicon> faviconCache;

    private boolean isPluginLoaded(String pluginName) {
        return getProxy().getPluginManager().getPlugin(pluginName) != null;
    }

    @Override
    public void onEnable() {
        // Check if RGB color codes are supproted
        if (colorize("&x&a&b&c&d&e&f").charAt(0) != '&') {
            this.rgbFormat = RGBFormat.WEIRD_BUNGEE;
        }

        try { // Load the core first
            ServerListPlusLogger clogger = new JavaServerListPlusLogger(getLogger(), ServerListPlusLogger.CORE_PREFIX);
            this.core = new ServerListPlusCore(this, clogger);
            getLogger().log(INFO, "Successfully loaded!");
        } catch (ServerListPlusException e) {
            getLogger().log(INFO, "Please fix the error before restarting the server!"); return;
        } catch (Exception e) {
            getLogger().log(ERROR, "An internal error occurred while loading the core.", e);
            return;
        }

        // Register commands
        getProxy().getPluginManager().registerCommand(this, new ServerListPlusCommand());

        try {
            if (isPluginLoaded("AdvancedBan")) {
                core.setBanProvider(new AdvancedBanBanProvider());
            } else if (isPluginLoaded("BungeeBan")) {
                core.setBanProvider(new BungeeBanBanProvider());
            }
        } catch (Throwable e) {
            getLogger().log(ERROR, "Failed to register ban provider", e);
        }
    }

    @Override
    public void onDisable() {
        if (core == null) return;
        try {
            core.stop();
        } catch (ServerListPlusException ignored) {}
    }

    // Commands
    public final class ServerListPlusCommand extends Command implements TabExecutor {
        private ServerListPlusCommand() {
            super("serverlistplus", "serverlistplus.command", "slp");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            core.executeCommand(new BungeeCommandSender(sender), getName(), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            return core.tabComplete(new BungeeCommandSender(sender), getName(), args);
        }
    }

    // Player tracking
    public final class ConnectionListener implements Listener {
        private ConnectionListener() {}

        @EventHandler
        public void onPlayerLogin(LoginEvent event) {
            handleConnection(event.getConnection());
        }

        @EventHandler
        public void onPlayerLogout(PlayerDisconnectEvent event) {
            handleConnection(event.getPlayer().getPendingConnection());
        }

        private void handleConnection(PendingConnection con) {
            if (core == null) return; // Too early, we haven't finished initializing yet
            core.updateClient(con.getAddress().getAddress(), con.getUniqueId(), con.getName());
        }
    }

    // Status listener
    public final class PingListener implements Listener {
        private PingListener() {}

        private final class ResolvedPassthrough {
            private final PassthroughConf.RuleConf rule;
            private final String hostname;
            private final String serverName;
            private final ServerPing backendPing;

            private ResolvedPassthrough(PassthroughConf.RuleConf rule, String hostname, String serverName, ServerPing backendPing) {
                this.rule = rule;
                this.hostname = hostname;
                this.serverName = serverName;
                this.backendPing = backendPing;
            }
        }

        @EventHandler
        public void onProxyPing(ProxyPingEvent event) {
            if (core == null) return; // Too early, we haven't finished initializing yet
            if (event.getResponse() == null) return; // Check if response is not empty

            PendingConnection con = event.getConnection();
            StatusRequest request = core.createRequest(con.getAddress().getAddress());

            request.setProtocolVersion(con.getVersion());
            InetSocketAddress host = con.getVirtualHost();
            ServerInfo forcedHost = null;
            if (host != null) {
                forcedHost = AbstractReconnectHandler.getForcedHost(con);
                request.setTarget(host, forcedHost != null ? forcedHost.getName() : null);
            }

            final ServerPing ping = event.getResponse();
            final ServerPing.Players players = ping.getPlayers();
            final ServerPing.Protocol version = ping.getVersion();

            String hostname = host != null ? StatusRequest.cleanVirtualHost(host.getHostString()) : null;
            ResolvedPassthrough passthrough = resolvePassthrough(hostname, forcedHost);
            if (passthrough != null) {
                ServerPing.Players backendPlayers = passthrough.backendPing.getPlayers();
                request.setPassthroughPlayerCount(passthrough.serverName,
                        backendPlayers != null ? backendPlayers.getOnline() : null,
                        backendPlayers != null ? backendPlayers.getMax() : null);
                request.setPassthroughPlayerCount(passthrough.hostname,
                        backendPlayers != null ? backendPlayers.getOnline() : null,
                        backendPlayers != null ? backendPlayers.getMax() : null);
            }

            StatusResponse response = request.createResponse(core.getStatus(),
                    // Return unknown player counts if it has been hidden
                    new ResponseFetcher() {
                        @Override
                        public Integer getOnlinePlayers() {
                            return players != null ? players.getOnline() : null;
                        }

                        @Override
                        public Integer getMaxPlayers() {
                            return players != null ? players.getMax() : null;
                        }

                        @Override
                        public int getProtocolVersion() {
                            return version != null ? version.getProtocol() : 0;
                        }
                    });

            // Description
            String description = response.getDescription();
            if (description != null) ping.setDescription(description);

            if (version != null) {
                // Version name
                String versionName = response.getVersion();
                if (versionName != null) version.setName(versionName);
                // Protocol version
                Integer protocol = response.getProtocolVersion();
                if (protocol != null) version.setProtocol(protocol);
            }

            if (players != null) {
                if (response.hidePlayers()) {
                    ping.setPlayers(null);
                } else {
                    // Online players
                    Integer onlinePlayers = response.getOnlinePlayers();
                    if (onlinePlayers != null) players.setOnline(onlinePlayers);
                    // Max players
                    Integer maxPlayers = response.getMaxPlayers();
                    if (maxPlayers != null) players.setMax(maxPlayers);

                    // Player hover
                    String playerHover = response.getPlayerHover();
                    if (playerHover != null) {
                        if (playerHover.isEmpty()) {
                            players.setSample(null);
                        } else {
                            List<String> lines = Helper.splitLinesToList(playerHover);

                            ServerPing.PlayerInfo[] sample = new ServerPing.PlayerInfo[lines.size()];
                            for (int i = 0; i < sample.length; i++)
                                sample[i] = new ServerPing.PlayerInfo(lines.get(i), UUIDs.EMPTY);

                            players.setSample(sample);
                        }
                    }
                }
            }

            // Favicon
            FaviconSource favicon = response.getFavicon();
            if (favicon == FaviconSource.NONE) {
                ping.setFavicon((Favicon) null);
            } else if (favicon != null) {
                Optional<Favicon> icon = faviconCache.getIfPresent(favicon);
                if (icon == null) {
                    // Load favicon asynchronously
                    event.registerIntent(BungeePlugin.this);
                    getProxy().getScheduler().runAsync(BungeePlugin.this, new AsyncFaviconLoader(event, favicon));
                } else if (icon.isPresent()) {
                    ping.setFavicon(icon.get());
                }
            }

            if (passthrough != null && passthrough.rule.Fields != null) {
                PassthroughConf.FieldsConf fields = passthrough.rule.Fields;
                ServerPing backendPing = passthrough.backendPing;
                ServerPing.Players backendPlayers = backendPing.getPlayers();
                ServerPing.Protocol backendVersion = backendPing.getVersion();

                if (fields.Motd && backendPing.getDescription() != null) {
                    ping.setDescription(backendPing.getDescription());
                }

                if (fields.PlayerCount && backendPlayers != null) {
                    if (ping.getPlayers() == null) {
                        ping.setPlayers(new ServerPing.Players(backendPlayers.getMax(),
                                backendPlayers.getOnline(), backendPlayers.getSample()));
                    } else {
                        ping.getPlayers().setOnline(backendPlayers.getOnline());
                        ping.getPlayers().setMax(backendPlayers.getMax());
                    }
                }
                if (fields.PlayerHover && ping.getPlayers() != null) {
                    ping.getPlayers().setSample(backendPlayers != null ? backendPlayers.getSample() : null);
                }

                if ((fields.VersionName || fields.ProtocolVersion) && backendVersion != null) {
                    if (ping.getVersion() == null) {
                        ping.setVersion(new ServerPing.Protocol(backendVersion.getName(), backendVersion.getProtocol()));
                    } else {
                        if (fields.VersionName) {
                            ping.getVersion().setName(backendVersion.getName());
                        }
                        if (fields.ProtocolVersion) {
                            ping.getVersion().setProtocol(backendVersion.getProtocol());
                        }
                    }
                }
                if (fields.Favicon) {
                    ping.setFavicon(extractBackendFavicon(backendPing));
                }
            }
        }

        private ResolvedPassthrough resolvePassthrough(String hostname, ServerInfo forcedHost) {
            if (hostname == null) {
                return null;
            }

            BungeeConf bungeeConf = core.getConf(BungeeConf.class);
            if (bungeeConf == null || bungeeConf.Passthrough == null || bungeeConf.Passthrough.Rules == null) {
                return null;
            }

            for (PassthroughConf.RuleConf rule : bungeeConf.Passthrough.Rules) {
                if (!matchesRule(rule, hostname)) {
                    continue;
                }

                ServerInfo target = resolveTargetServer(rule, hostname, forcedHost);
                if (target == null) {
                    continue;
                }

                try {
                    ServerPing backendPing = pingServer(target);
                    if (backendPing != null) {
                        return new ResolvedPassthrough(rule, hostname, target.getName(), backendPing);
                    }
                } catch (TimeoutException e) {
                    getLogger().log(DEBUG, "Timeout while pinging backend server for passthrough: " + target.getName());
                } catch (Exception e) {
                    getLogger().log(DEBUG, "Failed to ping backend server for passthrough: " + e.getMessage());
                }
            }

            return null;
        }

        private boolean matchesRule(PassthroughConf.RuleConf rule, String hostname) {
            if (rule == null || rule.Hosts == null) {
                return false;
            }
            for (String ruleHost : rule.Hosts) {
                if (ruleHost != null && hostname.equalsIgnoreCase(ruleHost)) {
                    return true;
                }
            }
            return false;
        }

        private ServerInfo resolveTargetServer(PassthroughConf.RuleConf rule, String hostname, ServerInfo forcedHost) {
            if (rule.TargetServer != null) {
                return getProxy().getServerInfo(rule.TargetServer);
            }
            if (forcedHost != null) {
                return forcedHost;
            }

            ServerInfo byHost = getProxy().getServerInfo(hostname);
            if (byHost != null) {
                return byHost;
            }

            if (rule.Hosts != null) {
                for (String ruleHost : rule.Hosts) {
                    if (ruleHost == null) {
                        continue;
                    }
                    ServerInfo byRuleHost = getProxy().getServerInfo(ruleHost);
                    if (byRuleHost != null) {
                        return byRuleHost;
                    }
                }
            }

            return null;
        }

    }

    private static ServerPing pingServer(ServerInfo server) throws Exception {
        CompletableFuture<ServerPing> future = new CompletableFuture<>();
        server.ping(new Callback<ServerPing>() {
            @Override
            public void done(ServerPing result, Throwable error) {
                if (error != null) {
                    future.completeExceptionally(error);
                } else {
                    future.complete(result);
                }
            }
        });
        return future.get(2, TimeUnit.SECONDS);
    }

    private static Favicon extractBackendFavicon(ServerPing backendPing) {
        try {
            Method getFaviconObject = backendPing.getClass().getMethod("getFaviconObject");
            Object favicon = getFaviconObject.invoke(backendPing);
            if (favicon instanceof Favicon) {
                return (Favicon) favicon;
            }
        } catch (Exception ignored) {}

        try {
            Method getFavicon = backendPing.getClass().getMethod("getFavicon");
            Object favicon = getFavicon.invoke(backendPing);
            if (favicon instanceof Favicon) {
                return (Favicon) favicon;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private final class AsyncFaviconLoader implements Runnable {

        private final ProxyPingEvent event;
        private final FaviconSource source;

        private AsyncFaviconLoader(ProxyPingEvent event, FaviconSource source) {
            this.event = event;
            this.source = source;
        }

        @Override
        public void run() {
            Optional<Favicon> favicon = faviconCache.get(this.source);
            if (favicon.isPresent()) {
                event.getResponse().setFavicon(favicon.get());
            }

            event.completeIntent(BungeePlugin.this);
        }

    }

    @Override
    public ServerListPlusCore getCore() {
        return core;
    }

    @Override
    public ServerType getServerType() {
        return ServerType.BUNGEE;
    }

    @Override
    public String getServerImplementation() {
        return getProxy().getName() + " " + getProxy().getVersion();
    }

    @Override
    public Integer getOnlinePlayers(String location) {
        ServerInfo server = getProxy().getServerInfo(location);
        return server != null ? server.getPlayers().size() : null;
    }

    @Override
    public Integer getMaxPlayers(String location) {
        ServerInfo server = getProxy().getServerInfo(location);
        if (server == null) {
            return null;
        }

        try {
            ServerPing ping = pingServer(server);
            ServerPing.Players players = ping != null ? ping.getPlayers() : null;
            return players != null ? players.getMax() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public Iterator<String> getRandomPlayers() {
        return getRandomPlayers(getProxy().getPlayers());
    }

    @Override
    public Iterator<String> getRandomPlayers(String location) {
        ServerInfo server = getProxy().getServerInfo(location);
        return server != null ? getRandomPlayers(server.getPlayers()) : null;
    }

    private static Iterator<String> getRandomPlayers(Collection<ProxiedPlayer> players) {
        if (Helper.isNullOrEmpty(players)) return null;

        List<String> result = new ArrayList<>(players.size());

        for (ProxiedPlayer player : players) {
            result.add(player.getName());
        }

        return Randoms.shuffle(result).iterator();
    }

    @Override
    public Cache<?, ?> getRequestCache() {
        return null;
    }

    @Override
    public FaviconCache<?> getFaviconCache() {
        return faviconCache;
    }

    @Override
    public void runAsync(Runnable task) {
        getProxy().getScheduler().runAsync(this, task);
    }

    @Override
    public ScheduledTask scheduleAsync(Runnable task, long repeat, TimeUnit unit) {
        return new ScheduledBungeeTask(getProxy().getScheduler().schedule(this, task, repeat, repeat, unit));
    }

    @Override
    public String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public RGBFormat getRGBFormat() {
        return rgbFormat;
    }

    @Override
    public void initialize(ServerListPlusCore core) {
        BungeeConf example = new BungeeConf();
        PassthroughConf.RuleConf rule = new PassthroughConf.RuleConf();
        rule.Hosts.add("lobby.example.com");
        rule.TargetServer = "lobby";
        rule.Fields.Motd = true;
        rule.Fields.PlayerCount = true;
        rule.Fields.PlayerHover = true;
        rule.Fields.VersionName = true;
        rule.Fields.ProtocolVersion = true;
        rule.Fields.Favicon = true;
        example.Passthrough.Rules.add(rule);

        core.registerConf(BungeeConf.class, new BungeeConf(), example, "Bungee");
    }

    @Override
    public void reloadCaches(ServerListPlusCore core) {

    }

    @Override
    public void createFaviconCache(CacheBuilderSpec spec) {
        if (faviconCache == null) {
            faviconCache = new FaviconCache<Favicon>(this, spec) {
                @Override
                protected Favicon createFavicon(BufferedImage image) throws Exception {
                    return Favicon.create(image);
                }
            };
        } else {
            faviconCache.reload(spec);
        }
    }

    @Override
    public void configChanged(ServerListPlusCore core, InstanceStorage<Object> confs) {
        // Player tracking
        if (confs.get(PluginConf.class).PlayerTracking.Enabled) {
            if (connectionListener == null) {
                registerListener(this.connectionListener = new ConnectionListener());
                getLogger().log(DEBUG, "Registered proxy player tracking listener.");
            }
        } else if (connectionListener != null) {
            unregisterListener(connectionListener);
            this.connectionListener = null;
            getLogger().log(DEBUG, "Unregistered proxy player tracking listener.");
        }
    }

    @Override
    public void statusChanged(StatusManager status, boolean hasChanges) {
        // Status listener
        if (hasChanges) {
            if (pingListener == null) {
                registerListener(this.pingListener = new PingListener());
                getLogger().log(DEBUG, "Registered proxy ping listener.");
            }
        } else if (pingListener != null) {
            unregisterListener(pingListener);
            this.pingListener = null;
            getLogger().log(DEBUG, "Unregistered proxy ping listener.");
        }
    }
}
