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
 */

package com.openlattice.datastore;

import com.geekbeast.mappers.mappers.ObjectMappers;
import com.geekbeast.rhizome.configuration.websockets.BaseRhizomeServer;
import com.geekbeast.rhizome.core.RhizomeApplicationServer;
import com.geekbeast.rhizome.hazelcast.serializers.RhizomeUtils.Pods;
import com.openlattice.auditing.pods.AuditingConfigurationPod;
import com.geekbeast.auth0.Auth0Pod;
import com.geekbeast.aws.AwsS3Pod;
import com.openlattice.data.serializers.FullQualifiedNameJacksonSerializer;
import com.openlattice.datastore.pods.ByteBlobServicePod;
import com.openlattice.datastore.pods.DatastoreSecurityPod;
import com.openlattice.datastore.pods.DatastoreServicesPod;
import com.openlattice.datastore.pods.DatastoreServletsPod;
import com.openlattice.hazelcast.pods.HazelcastQueuePod;
import com.openlattice.hazelcast.pods.MapstoresPod;
import com.openlattice.hazelcast.pods.NearCachesPod;
import com.openlattice.hazelcast.pods.SharedStreamSerializersPod;
import com.openlattice.ioc.providers.LateInitProvidersPod;
import com.geekbeast.jdbc.JdbcPod;
import com.geekbeast.postgres.PostgresPod;
import com.openlattice.postgres.pods.ExternalDatabaseConnectionManagerPod;
import com.geekbeast.pods.TaskSchedulerPod;

public class Datastore extends BaseRhizomeServer {
    private static final Class<?>[] datastorePods = new Class<?>[] {
            AuditingConfigurationPod.class,
            Auth0Pod.class,
            AwsS3Pod.class,
            ByteBlobServicePod.class,
            DatastoreServicesPod.class,
            ExternalDatabaseConnectionManagerPod.class,
            HazelcastQueuePod.class,
            JdbcPod.class,
            MapstoresPod.class,
            NearCachesPod.class,
            PostgresPod.class,
            SharedStreamSerializersPod.class,
            TaskSchedulerPod.class,
            // TransporterPod.class,
            // TransporterConfigurationPod.class,
            LateInitProvidersPod.class
    };

    private static final Class<?>[] webPods       = new Class<?>[] {
            DatastoreServletsPod.class,
            DatastoreSecurityPod.class, };

    static {
        ObjectMappers.foreach( FullQualifiedNameJacksonSerializer::registerWithMapper );
    }

    public Datastore( Class<?>... pods ) {
        super( Pods.concatenate(
                pods,
                webPods,
                RhizomeApplicationServer.DEFAULT_PODS,
                datastorePods ) );
    }

    @Override public void start( String... profiles ) throws Exception {
        super.start( profiles );
    }

    public static void main( String[] args ) throws Exception {
        Datastore datastore = new Datastore();
        datastore.start( args );
    }
}
