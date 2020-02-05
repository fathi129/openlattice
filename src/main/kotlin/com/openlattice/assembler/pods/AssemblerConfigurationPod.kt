/*
 * Copyright (C) 2019. OpenLattice, Inc.
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

package com.openlattice.assembler.pods

import com.kryptnostic.rhizome.pods.ConfigurationLoader
import com.openlattice.assembler.AssemblerConfiguration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.inject.Inject

private val logger = LoggerFactory.getLogger(AssemblerConfigurationPod::class.java)

/**
 *
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Configuration
class AssemblerConfigurationPod {
    @Inject
    private lateinit var configurationLoader: ConfigurationLoader

    @Bean
    fun assemblerConfiguration(): AssemblerConfiguration {
        return configurationLoader.logAndLoad( "assembler", AssemblerConfiguration::class.java)
    }
}
