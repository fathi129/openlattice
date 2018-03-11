/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.conductor;

import com.geekbeast.rhizome.NetworkUtils;
import com.openlattice.Conductor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public class ConductorBootstrap {
    protected static final Conductor conductor;

    static {
        conductor = new Conductor();
        //TODO: Fix this to be bamboo.openlattice.com as servers may not always be co-resident.
        if ( NetworkUtils.isRunningOnHost( "artifactory.openlattice.com" ) ) {
            LoggerFactory.getLogger( ConductorBootstrap.class ).info("Running on bamboo!");
            conductor.sprout( "awstest", "postgres" );
        } else {
            LoggerFactory.getLogger( ConductorBootstrap.class ).info("Not running on bamboo!");
            conductor.sprout( "local", "postgres" );
        }
    }

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

}
