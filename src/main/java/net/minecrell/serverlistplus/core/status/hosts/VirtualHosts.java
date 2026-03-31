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

package net.minecrell.serverlistplus.core.status.hosts;

import com.google.common.collect.ImmutableList;
import net.minecrell.serverlistplus.core.util.Helper;

import java.util.List;
import java.util.regex.Pattern;

public final class VirtualHosts {
    private VirtualHosts() {}
    private static final Pattern LIST_SEPARATOR = Pattern.compile(",", Pattern.LITERAL);

    public static VirtualHost parse(String host) {
        String[] list = LIST_SEPARATOR.split(host);
        if (list.length > 1) {
            ImmutableList.Builder<VirtualHost> builder = ImmutableList.builder();
            for (String token : list) {
                token = token.trim();
                if (!token.isEmpty()) {
                    builder.add(parseSingle(token));
                }
            }

            List<VirtualHost> hosts = builder.build();
            if (hosts.isEmpty()) return parseSingle(host);
            if (hosts.size() == 1) return hosts.get(0);
            return new VirtualHostGroup(hosts);
        }

        return parseSingle(host);
    }

    private static VirtualHost parseSingle(String host) {
        if (Helper.startsWithIgnoreCase(host, VirtualNamedHost.NAME_PREFIX))
            return VirtualNamedHost.parse(host.substring(VirtualNamedHost.NAME_PREFIX.length()));
        else return VirtualHostAddress.parse(host);
    }
}
