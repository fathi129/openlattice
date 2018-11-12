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

package com.openlattice.indexing.pods;

import static com.openlattice.linking.MatcherKt.DL4J;
import static com.openlattice.linking.MatcherKt.KERAS;

import com.google.common.io.Resources;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.hazelcast.core.HazelcastInstance;
import com.kryptnostic.rhizome.configuration.service.ConfigurationService.StaticLoader;
import com.openlattice.ResourceConfigurationLoader;
import com.openlattice.conductor.rpc.ConductorConfiguration;
import com.openlattice.conductor.rpc.ConductorElasticsearchApi;
import com.openlattice.data.EntityKeyIdService;
import com.openlattice.data.ids.PostgresEntityKeyIdService;
import com.openlattice.data.storage.PostgresEntityDataQueryService;
import com.openlattice.datastore.services.EdmManager;
import com.openlattice.ids.HazelcastIdGenerationService;
import com.openlattice.indexing.BackgroundIndexingService;
import com.openlattice.indexing.configuration.LinkingConfiguration;
import com.openlattice.kindling.search.ConductorElasticsearchImpl;
import com.openlattice.linking.Blocker;
import com.openlattice.linking.DataLoader;
import com.openlattice.linking.EdmCachingDataLoader;
import com.openlattice.linking.LinkingQueryService;
import com.openlattice.linking.Matcher;
import com.openlattice.linking.RealtimeLinkingService;
import com.openlattice.linking.blocking.ElasticsearchBlocker;
import com.openlattice.linking.graph.PostgresLinkingQueryService;
import com.openlattice.linking.matching.SocratesMatcher;
import com.openlattice.linking.util.PersonProperties;
import com.openlattice.search.EsEdmService;
import com.zaxxer.hikari.HikariDataSource;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import org.eclipse.jetty.plus.jndi.Link;
import org.nd4j.linalg.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.inject.Inject;
import java.io.IOException;
import org.springframework.context.annotation.Profile;

@Configuration
public class IndexerPostConfigurationServicesPod {

    @Inject
    private HazelcastInstance hazelcastInstance;

    @Inject
    private ConductorConfiguration conductorConfiguration;

    @Inject
    private HikariDataSource hikariDataSource;

    @Inject
    private EdmManager edm;

    @Inject
    private ListeningExecutorService executor;

    @Inject
    private Matcher matcher;

    @Bean
    public ConductorElasticsearchApi elasticsearchApi() throws IOException {
        return new ConductorElasticsearchImpl( conductorConfiguration.getSearchConfiguration() );
    }

    @Bean
    public HazelcastIdGenerationService idGeneration() {
        return new HazelcastIdGenerationService( hazelcastInstance );
    }

    @Bean
    public EntityKeyIdService idService() {
        return new PostgresEntityKeyIdService( hazelcastInstance, hikariDataSource, idGeneration() );
    }

    @Bean
    public PostgresEntityDataQueryService dataQueryService() {
        return new PostgresEntityDataQueryService( hikariDataSource );
    }

    @Bean
    public BackgroundIndexingService backgroundIndexingService() throws IOException {
        return new BackgroundIndexingService( hikariDataSource,
                hazelcastInstance,
                dataQueryService(),
                elasticsearchApi() );
    }

    @Bean
    public EsEdmService esEdmService() throws IOException {
        return new EsEdmService( elasticsearchApi() );
    }

    @Bean
    public DataLoader dataLoader() {
        return new EdmCachingDataLoader( dataQueryService(), hazelcastInstance );
    }

    @Bean
    public Blocker blocker() throws IOException {
        return new ElasticsearchBlocker( elasticsearchApi(), dataQueryService(), dataLoader(), hazelcastInstance );
    }

    @Bean public LinkingQueryService lqs() {
        return new PostgresLinkingQueryService( hikariDataSource );
    }

    @Bean
    public LinkingConfiguration linkingConfiguration() {
        return ResourceConfigurationLoader.loadConfiguration( LinkingConfiguration.class );
    }

    @Bean
    public RealtimeLinkingService linkingService() throws IOException {
        var lc = linkingConfiguration();
        return new RealtimeLinkingService( hazelcastInstance,
                blocker(),
                matcher,
                idService(),
                dataLoader(),
                lqs(),
                executor,
                edm.getEntityTypeUuids( lc.getEntityTypes() ),
                lc.getBlacklist(),
                lc.getBlockSize() );
    }
}
