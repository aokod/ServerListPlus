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

package net.minecrell.serverlistplus.velocity;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilderSpec;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import com.velocitypowered.api.util.ProxyVersion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.ServerListPlusException;
import net.minecrell.serverlistplus.core.config.PassthroughConf;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.config.storage.InstanceStorage;
import net.minecrell.serverlistplus.core.favicon.FaviconCache;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.logging.ServerListPlusLogger;
import net.minecrell.serverlistplus.core.logging.Slf4jServerListPlusLogger;
import net.minecrell.serverlistplus.core.plugin.MaxPlayersProvider;
import net.minecrell.serverlistplus.core.plugin.ScheduledTask;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.replacement.rgb.RGBFormat;
import net.minecrell.serverlistplus.core.status.ResponseFetcher;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusRequest;
import net.minecrell.serverlistplus.core.status.StatusResponse;
import net.minecrell.serverlistplus.core.util.FormattingCodes;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.Randoms;
import net.minecrell.serverlistplus.core.util.UUIDs;
import net.minecrell.serverlistplus.velocity.config.VelocityConf;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Plugin(id = "serverlistplus", name = "ServerListPlus", version = "%version%",
    description = "%description%", url = "%url%", authors = {"%author%"})
public class VelocityPlugin implements ServerListPlusPlugin, MaxPlayersProvider {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder().hexColors().build();

    private final Logger logger;

    private final ProxyServer proxy;
    private final Path pluginFolder;

    private ServerListPlusCore core;
    private EventHandler<ProxyPingEvent> pingListener;
    private Object connectionListener;
    private boolean legacyPassthroughWarningLogged;

    private FaviconCache<Favicon> faviconCache;

    @Inject
    public VelocityPlugin(Logger logger, ProxyServer proxy, @DataDirectory Path pluginFolder) {
        this.logger = logger;
        this.proxy = proxy;
        this.pluginFolder = pluginFolder;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try { // Load the core first
            ServerListPlusLogger clogger = new Slf4jServerListPlusLogger(this.logger, ServerListPlusLogger.CORE_PREFIX);
            this.core = new ServerListPlusCore(this, clogger);
            logger.info("Successfully loaded!");
        } catch (ServerListPlusException e) {
            logger.info("Please fix the error before restarting the server!");
            return;
        } catch (Exception e) {
            logger.error("An internal error occurred while loading the core.", e);
            return;
        }

        // Register commands
        this.proxy.getCommandManager().register("serverlistplus", new ServerListPlusCommand(), "slp");
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        if (core == null) return;
        try {
            core.stop();
        } catch (ServerListPlusException ignored) {}
    }

    // Commands
    public final class ServerListPlusCommand implements SimpleCommand {
        private ServerListPlusCommand() {}

        @Override
        public void execute(Invocation invocation) {
            core.executeCommand(new VelocityCommandSender(proxy, invocation.source()),
                    invocation.alias(), invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return core.tabComplete(new VelocityCommandSender(proxy, invocation.source()),
                    invocation.alias(), invocation.arguments());
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("serverlistplus.command");
        }
    }

    // Player tracking
    public final class ConnectionListener {
        private ConnectionListener() {}

        @Subscribe
        public void onPlayerLogin(LoginEvent event) {
            handleConnection(event.getPlayer());
        }

        @Subscribe
        public void onPlayerLogout(DisconnectEvent event) {
            handleConnection(event.getPlayer());
        }

        private void handleConnection(Player player) {
            if (core == null) return; // Too early, we haven't finished initializing yet
            core.updateClient(player.getRemoteAddress().getAddress(), player.getUniqueId(), player.getUsername());
        }
    }

    // Status listener
    public final class PingListener implements EventHandler<ProxyPingEvent> {
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

        @Override
        public void execute(ProxyPingEvent event) {
            if (core == null) return; // Too early, we haven't finished initializing yet

            InboundConnection con = event.getConnection();
            StatusRequest request = core.createRequest(con.getRemoteAddress().getAddress());

            request.setProtocolVersion(con.getProtocolVersion().getProtocol());
            
            Optional<InetSocketAddress> virtualHostOpt = con.getVirtualHost();
            virtualHostOpt.ifPresent(request::setTarget);

            final ServerPing ping = event.getPing();
            final ServerPing.Players players = ping.getPlayers().orElse(null);
            final ServerPing.Version version = ping.getVersion();

            ResolvedPassthrough passthrough = resolvePassthrough(virtualHostOpt);
            if (passthrough != null) {
                ServerPing.Players backendPlayers = passthrough.backendPing.getPlayers().orElse(null);
                request.setPassthroughPlayerCount(passthrough.serverName,
                        backendPlayers != null ? backendPlayers.getOnline() : null,
                        backendPlayers != null ? backendPlayers.getMax() : null);
                request.setPassthroughPlayerCount(passthrough.hostname,
                        backendPlayers != null ? backendPlayers.getOnline() : null,
                        backendPlayers != null ? backendPlayers.getMax() : null);
            }

            applyProxyMOTD(event, request, ping, players, version, passthrough);
        }

        private void applyProxyMOTD(ProxyPingEvent event, StatusRequest request, 
                ServerPing ping, ServerPing.Players players, ServerPing.Version version, ResolvedPassthrough passthrough) {
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

            ServerPing.Builder builder = ping.asBuilder();

            // Description
            String description = response.getDescription();
            if (description != null) builder.description(LEGACY_SERIALIZER.deserialize(description));

            if (version != null) {
                // Version name
                String versionName = response.getVersion();
                // Protocol version
                Integer protocol = response.getProtocolVersion();

                if (versionName != null || protocol != null) builder.version(new ServerPing.Version(
                        protocol != null ? protocol : version.getProtocol(),
                        versionName != null ? versionName : version.getName()
                ));
            }

            if (players != null) {
                if (response.hidePlayers()) {
                    builder.nullPlayers();
                } else {
                    // Online players
                    Integer onlinePlayers = response.getOnlinePlayers();
                    if (onlinePlayers != null) builder.onlinePlayers(onlinePlayers);
                    // Max players
                    Integer maxPlayers = response.getMaxPlayers();
                    if (maxPlayers != null) builder.maximumPlayers(maxPlayers);

                    // Player hover
                    String playerHover = response.getPlayerHover();
                    if (playerHover != null) {
                        builder.clearSamplePlayers();

                        if (!playerHover.isEmpty()) {
                            List<String> lines = Helper.splitLinesToList(playerHover);

                            ServerPing.SamplePlayer[] sample = new ServerPing.SamplePlayer[lines.size()];
                            for (int i = 0; i < sample.length; i++)
                                sample[i] = new ServerPing.SamplePlayer(lines.get(i), UUIDs.EMPTY);

                            builder.samplePlayers(sample);
                        }
                    }
                }
            }

            // Favicon
            FaviconSource favicon = response.getFavicon();
            if (favicon == FaviconSource.NONE) {
                builder.clearFavicon();
            } else if (favicon != null) {
                com.google.common.base.Optional<Favicon> icon = faviconCache.get(favicon);
                if (icon.isPresent())
                    builder.favicon(icon.get());
            }

            if (passthrough != null && passthrough.rule.Fields != null) {
                PassthroughConf.FieldsConf fields = passthrough.rule.Fields;
                ServerPing backendPing = passthrough.backendPing;
                ServerPing.Players backendPlayers = backendPing.getPlayers().orElse(null);
                ServerPing.Version backendVersion = backendPing.getVersion();

                if (fields.Motd && backendPing.getDescriptionComponent() != null) {
                    builder.description(backendPing.getDescriptionComponent());
                }
                if (fields.PlayerCount && backendPlayers != null) {
                    builder.onlinePlayers(backendPlayers.getOnline());
                    builder.maximumPlayers(backendPlayers.getMax());
                }
                if (fields.PlayerHover) {
                    builder.clearSamplePlayers();
                    ServerPing.SamplePlayer[] backendSample = extractBackendSamplePlayers(backendPlayers);
                    if (backendSample != null && backendSample.length > 0) {
                        builder.samplePlayers(backendSample);
                    }
                }
                if ((fields.VersionName || fields.ProtocolVersion) && backendVersion != null) {
                    builder.version(new ServerPing.Version(
                            fields.ProtocolVersion ? backendVersion.getProtocol() :
                                    (version != null ? version.getProtocol() : backendVersion.getProtocol()),
                            fields.VersionName ? backendVersion.getName() :
                                    (version != null ? version.getName() : backendVersion.getName())
                    ));
                }
                if (fields.Favicon) {
                    builder.clearFavicon();
                    Optional<Favicon> backendFavicon = extractBackendFavicon(backendPing);
                    if (backendFavicon.isPresent()) {
                        builder.favicon(backendFavicon.get());
                    }
                }
            }

            event.setPing(builder.build());
        }

        private ResolvedPassthrough resolvePassthrough(Optional<InetSocketAddress> virtualHostOpt) {
            if (!virtualHostOpt.isPresent()) {
                return null;
            }

            VelocityConf velocityConf = core.getConf(VelocityConf.class);
            if (velocityConf == null) {
                return null;
            }

            String hostname = virtualHostOpt.get().getHostString();
            PassthroughConf.RuleConf rule = findRule(velocityConf, hostname);
            if (rule == null) {
                return null;
            }

            Optional<RegisteredServer> targetServer = resolveTargetServer(rule, hostname);
            if (!targetServer.isPresent()) {
                return null;
            }

            RegisteredServer server = targetServer.get();
            try {
                CompletableFuture<ServerPing> serverPingFuture = server.ping();
                ServerPing serverPing = serverPingFuture.get(2, TimeUnit.SECONDS);
                if (serverPing != null) {
                    return new ResolvedPassthrough(rule, hostname, server.getServerInfo().getName(), serverPing);
                }
            } catch (TimeoutException e) {
                logger.debug("Timeout while pinging backend server for passthrough: " + server.getServerInfo().getName());
            } catch (Exception e) {
                logger.debug("Failed to ping backend server for passthrough: " + e.getMessage());
            }

            return null;
        }

        private PassthroughConf.RuleConf findRule(VelocityConf velocityConf, String hostname) {
            List<PassthroughConf.RuleConf> rules = getConfiguredRules(velocityConf);
            for (PassthroughConf.RuleConf rule : rules) {
                if (rule == null || rule.Hosts == null) {
                    continue;
                }
                for (String ruleHost : rule.Hosts) {
                    if (ruleHost != null && hostname.equalsIgnoreCase(ruleHost)) {
                        return rule;
                    }
                }
            }
            return null;
        }

        private List<PassthroughConf.RuleConf> getConfiguredRules(VelocityConf velocityConf) {
            if (velocityConf.Passthrough != null && velocityConf.Passthrough.Rules != null
                    && !velocityConf.Passthrough.Rules.isEmpty()) {
                return velocityConf.Passthrough.Rules;
            }

            if (velocityConf.PingPassthrough == null || velocityConf.PingPassthrough.EnabledHostnames == null
                    || velocityConf.PingPassthrough.EnabledHostnames.isEmpty()) {
                return Collections.emptyList();
            }

            List<PassthroughConf.RuleConf> translatedRules = new ArrayList<>();
            for (String host : velocityConf.PingPassthrough.EnabledHostnames) {
                if (host == null) {
                    continue;
                }
                PassthroughConf.RuleConf rule = new PassthroughConf.RuleConf();
                rule.Hosts.add(host);
                if (velocityConf.PingPassthrough.ServerMappings != null) {
                    String mapped = findServerMapping(velocityConf.PingPassthrough.ServerMappings, host);
                    if (mapped != null) {
                        rule.TargetServer = mapped;
                    }
                }
                rule.Fields.Motd = true;
                translatedRules.add(rule);
            }
            return translatedRules;
        }

        private String findServerMapping(java.util.Map<String, String> mappings, String host) {
            String mapped = mappings.get(host);
            if (mapped != null) {
                return mapped;
            }
            for (java.util.Map.Entry<String, String> entry : mappings.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(host)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private Optional<RegisteredServer> resolveTargetServer(PassthroughConf.RuleConf rule, String hostname) {
            if (rule.TargetServer != null) {
                return proxy.getServer(rule.TargetServer);
            }

            Optional<RegisteredServer> byHost = proxy.getServer(hostname);
            if (byHost.isPresent()) {
                return byHost;
            }

            if (rule.Hosts != null) {
                for (String host : rule.Hosts) {
                    if (host == null) {
                        continue;
                    }
                    Optional<RegisteredServer> byRuleHost = proxy.getServer(host);
                    if (byRuleHost.isPresent()) {
                        return byRuleHost;
                    }
                }
            }

            return Optional.empty();
        }

        private ServerPing.SamplePlayer[] extractBackendSamplePlayers(ServerPing.Players backendPlayers) {
            if (backendPlayers == null) {
                return null;
            }

            try {
                Method getSample = backendPlayers.getClass().getMethod("getSample");
                Object sample = getSample.invoke(backendPlayers);
                if (sample instanceof Collection) {
                    Collection<?> collection = (Collection<?>) sample;
                    ServerPing.SamplePlayer[] result = new ServerPing.SamplePlayer[collection.size()];
                    int i = 0;
                    for (Object obj : collection) {
                        if (!(obj instanceof ServerPing.SamplePlayer)) {
                            return null;
                        }
                        result[i++] = (ServerPing.SamplePlayer) obj;
                    }
                    return result;
                } else if (sample instanceof ServerPing.SamplePlayer[]) {
                    return (ServerPing.SamplePlayer[]) sample;
                }
            } catch (Exception ignored) {}

            return null;
        }

        private Optional<Favicon> extractBackendFavicon(ServerPing backendPing) {
            try {
                Method getFavicon = backendPing.getClass().getMethod("getFavicon");
                Object favicon = getFavicon.invoke(backendPing);
                if (favicon instanceof Optional) {
                    Optional<?> optional = (Optional<?>) favicon;
                    if (!optional.isPresent()) {
                        return Optional.empty();
                    }
                    Object value = optional.get();
                    if (value instanceof Favicon) {
                        return Optional.of((Favicon) value);
                    }
                }
            } catch (Exception ignored) {}
            return Optional.empty();
        }
    }

    @Override
    public ServerListPlusCore getCore() {
        return core;
    }

    @Override
    public ServerType getServerType() {
        return ServerType.VELOCITY;
    }

    @Override
    public String getServerImplementation() {
        ProxyVersion version = this.proxy.getVersion();
        return version.getName() + " " + version.getVersion();
    }

    @Override
    public Path getPluginFolder() {
        return this.pluginFolder;
    }

    @Override
    public Integer getOnlinePlayers(String location) {
        Optional<RegisteredServer> server = proxy.getServer(location);
        if (!server.isPresent()) {
            return null;
        }

        return server.get().getPlayersConnected().size();
    }

    @Override
    public Integer getMaxPlayers(String location) {
        Optional<RegisteredServer> server = proxy.getServer(location);
        if (!server.isPresent()) {
            return null;
        }

        try {
            ServerPing ping = server.get().ping().get(2, TimeUnit.SECONDS);
            if (ping == null || !ping.getPlayers().isPresent()) {
                return null;
            }
            return ping.getPlayers().get().getMax();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public Iterator<String> getRandomPlayers() {
        Collection<Player> players = this.proxy.getAllPlayers();
        if (Helper.isNullOrEmpty(players)) return null;

        List<String> result = new ArrayList<>(players.size());
        for (Player player : players) {
            result.add(player.getUsername());
        }

        return Randoms.shuffle(result).iterator();
    }

    @Override
    public Iterator<String> getRandomPlayers(String location) {
        Optional<RegisteredServer> server = proxy.getServer(location);
        if (!server.isPresent()) {
            return null;
        }

        ArrayList<String> result = new ArrayList<>();
        for (Player player : server.get().getPlayersConnected()) {
            result.add(player.getUsername());
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
        proxy.getScheduler().buildTask(this, task).schedule();
    }

    @Override
    public ScheduledTask scheduleAsync(Runnable task, long repeat, TimeUnit unit) {
        return new ScheduledVelocityTask(proxy.getScheduler()
                .buildTask(this, task)
                .delay((int) repeat, unit)
                .repeat((int) repeat, unit)
                .schedule());
    }

    @Override
    public String colorize(String s) {
        return FormattingCodes.colorizeHex(s);
    }

    @Override
    public RGBFormat getRGBFormat() {
        return RGBFormat.ADVENTURE;
    }

    @Override
    public void initialize(ServerListPlusCore core) {
        // Register Velocity-specific configuration with example
        VelocityConf example = new VelocityConf();
        PassthroughConf.RuleConf rule = new PassthroughConf.RuleConf();
        rule.Hosts.add("example.com");
        rule.TargetServer = "lobby";
        rule.Fields.Motd = true;
        rule.Fields.PlayerCount = true;
        rule.Fields.PlayerHover = true;
        rule.Fields.VersionName = true;
        rule.Fields.ProtocolVersion = true;
        rule.Fields.Favicon = true;
        example.Passthrough.Rules.add(rule);
        
        core.registerConf(VelocityConf.class, new VelocityConf(), example, "Velocity");
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
        VelocityConf velocityConf = confs.get(VelocityConf.class);
        if (velocityConf != null
                && velocityConf.PingPassthrough != null
                && velocityConf.PingPassthrough.EnabledHostnames != null
                && !velocityConf.PingPassthrough.EnabledHostnames.isEmpty()
                && !legacyPassthroughWarningLogged) {
            logger.warn("Velocity.PingPassthrough is deprecated. Please migrate to Velocity.Passthrough.Rules.");
            legacyPassthroughWarningLogged = true;
        }

        // Player tracking
        if (confs.get(PluginConf.class).PlayerTracking.Enabled) {
            if (connectionListener == null) {
                this.proxy.getEventManager().register(this, this.connectionListener = new ConnectionListener());
                logger.debug("Registered player tracking listener.");
            }
        } else if (connectionListener != null) {
            this.proxy.getEventManager().unregisterListener(this, connectionListener);
            this.connectionListener = null;
            logger.debug("Unregistered player tracking listener.");
        }
    }

    @Override
    public void statusChanged(StatusManager status, boolean hasChanges) {
        // Status listener
        if (hasChanges) {
            if (pingListener == null) {
                this.proxy.getEventManager().register(this, ProxyPingEvent.class, this.pingListener = new PingListener());
                logger.debug("Registered ping listener.");
            }
        } else if (pingListener != null) {
            this.proxy.getEventManager().unregister(this, this.pingListener);
            this.pingListener = null;
            logger.debug("Unregistered ping listener.");
        }
    }

}
