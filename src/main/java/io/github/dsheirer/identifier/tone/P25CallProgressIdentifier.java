/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */

package io.github.dsheirer.identifier.tone;

import io.github.dsheirer.protocol.Protocol;

import java.util.List;

public class P25CallProgressIdentifier extends CallProgressIdentifier
{
    public P25CallProgressIdentifier(List<String> values)
    {
        super(values);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.APCO25;
    }

    public static P25CallProgressIdentifier create(List<String> values)
    {
        return new P25CallProgressIdentifier(values);
    }
}
