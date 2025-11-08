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

package net.minecrell.serverlistplus.velocity.config;

import net.minecrell.serverlistplus.core.config.help.Description;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Description({
        "PingPassthrough: Configuration for ping-passthrough functionality in Velocity.",
        "  - When enabled for a hostname, the proxy will display the backend server's MOTD",
        "    instead of the proxy server's MOTD.",
        "  - EnabledHostnames: List of hostnames (IP addresses or domain names) that should",
        "    use ping-passthrough. Default behavior is to show proxy MOTD.",
        "  - ServerMappings: Map hostnames to specific backend server names (as defined in",
        "    Velocity's config). If a hostname is not mapped, Velocity will use its normal",
        "    server resolution (forced-hosts, etc.).",
        "",
        "Example:",
        "  EnabledHostnames:",
        "    - example.com",
        "    - 192.168.1.100",
        "  ServerMappings:",
        "    example.com: lobby",
        "    192.168.1.100: hub"
})
public class VelocityConf {
    public PingPassthroughConf PingPassthrough = new PingPassthroughConf();

    public static class PingPassthroughConf {
        public List<String> EnabledHostnames = null;
        public Map<String, String> ServerMappings = new HashMap<>();

        public PingPassthroughConf() {}
    }
}

