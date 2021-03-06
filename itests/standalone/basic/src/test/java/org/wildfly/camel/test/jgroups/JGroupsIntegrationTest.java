/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.camel.test.jgroups;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jgroups.JGroupsFilters;
import org.apache.camel.impl.DefaultCamelContext;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.extension.camel.CamelAware;

@CamelAware
@RunWith(Arquillian.class)
public class JGroupsIntegrationTest {

    String master;

    @Deployment
    public static JavaArchive createdeployment() throws IOException {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "camel-jgroups-tests");
        return archive;
    }

    @Test
    public void testMasterElection() throws Exception {

        final CountDownLatch latch = new CountDownLatch(1);
        
        CamelContext camelcxt = new DefaultCamelContext();
        camelcxt.addRoutes(new RouteBuilder() {
            public void configure() throws Exception {
                String jgroupsEndpoint = String.format("jgroups:%s?enableViewMessages=true", UUID.randomUUID());
                from(jgroupsEndpoint).filter(JGroupsFilters.dropNonCoordinatorViews()).process(new Processor() {
                    @Override
                    public void process(Exchange exchange) throws Exception {
                        String camelContextName = exchange.getContext().getName();
                        if (!camelContextName.equals(master)) {
                            master = camelContextName;
                            System.out.println("ELECTED MASTER: " + master);
                            latch.countDown();
                        }
                    }
                });
            }
        });

        camelcxt.start();
        try {
            Assert.assertTrue(latch.await(3, TimeUnit.SECONDS));
            Assert.assertEquals(camelcxt.getName(), master);
        } finally {
            camelcxt.stop();
        }
    }
}
