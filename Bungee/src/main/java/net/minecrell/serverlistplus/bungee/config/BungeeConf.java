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

package net.minecrell.serverlistplus.bungee.config;

import net.minecrell.serverlistplus.core.config.PassthroughConf;
import net.minecrell.serverlistplus.core.config.help.Description;

@Description({
        "Passthrough: Granular backend ping passthrough for matched hostnames.",
        "  - ServerListPlus configuration is applied first, then selected fields can",
        "    be overwritten from the backend ping response.",
        "  - Fields: Motd, PlayerCount, PlayerHover, VersionName, ProtocolVersion, Favicon",
        "",
        "Example:",
        "  Rules:",
        "    - Hosts:",
        "      - lobby.example.com",
        "      TargetServer: lobby",
        "      Fields:",
        "        Motd: true",
        "        PlayerCount: true",
        "        PlayerHover: true",
        "        VersionName: true",
        "        ProtocolVersion: true",
        "        Favicon: true"
})
public class BungeeConf {
    public PassthroughConf Passthrough = new PassthroughConf();
}
