/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

import javax.inject.Singleton;

@OpenAPIDefinition(
        info = @Info(
                title = "Elassandra Operator",
                version = "0.1",
                description = "Strapdata Elassandra Kubernetes Operator",
                license = @License(name = "AGPL", url = "https://www.gnu.org/licenses/agpl-3.0.fr.html")
        )
)
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class);
    }

    @Singleton
    static class ObjectMapperBeanEventListener implements BeanCreatedEventListener<ObjectMapper> {

        /**
         * Register the fabric8 datacenter+task deserializer for webhook admission.
         * @param event
         * @return
         */
        @Override
        public ObjectMapper onCreated(BeanCreatedEvent<ObjectMapper> event) {
            final ObjectMapper mapper = event.getBean();
            KubernetesDeserializer.registerCustomKind(StrapdataCrdGroup.GROUP + "/" + Task.VERSION, Task.KIND, com.strapdata.strapkop.model.fabric8.task.Task.class);
            KubernetesDeserializer.registerCustomKind(StrapdataCrdGroup.GROUP + "/" + DataCenter.VERSION, DataCenter.KIND, com.strapdata.strapkop.model.fabric8.datacenter.DataCenter.class);

            SimpleModule module = new SimpleModule();
            module.addDeserializer(Object.class, new KubernetesDeserializer());
            mapper.registerModule(module);
            return mapper;
        }
    }
}