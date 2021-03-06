/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wildfly.camel.test.ldap;

import java.io.File;
import java.net.InetAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.SimpleRegistry;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.directory.api.ldap.codec.api.LdapApiService;
import org.apache.directory.api.ldap.codec.standalone.StandaloneLdapApiService;
import org.apache.directory.api.ldap.util.JndiUtils;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.annotations.ApplyLdifFiles;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.camel.test.common.utils.AvailablePortFinder;
import org.wildfly.camel.test.ldap.DirectoryServiceBuilder.SetupResult;
import org.wildfly.extension.camel.CamelAware;
import org.wildfly.extension.camel.CamelContextRegistry;

@CamelAware
@RunWith(Arquillian.class)
@ServerSetup({ LdapIntegrationTest.LdapServerSetupTask.class })
public class LdapIntegrationTest {

    @ArquillianResource
    CamelContextRegistry contextRegistry;

    @CreateLdapServer(transports = { @CreateTransport(protocol = "LDAP") })
    @ApplyLdifFiles("ldap/LdapRouteTest.ldif")
    static class LdapServerSetupTask implements ServerSetupTask {

        private SetupResult setupResult;

        @Override
        public void setup(final ManagementClient managementClient, String containerId) throws Exception {
            setupResult = DirectoryServiceBuilder.setupDirectoryService(LdapServerSetupTask.class);
            int port = setupResult.getLdapServer().getPort();
            AvailablePortFinder.storeServerData("ldap-port", port);
        }

        @Override
        public void tearDown(final ManagementClient managementClient, String containerId) throws Exception {
            if (setupResult != null) {
                setupResult.getLdapServer().stop();
                DirectoryServiceBuilder.shutdownDirectoryService(setupResult.getDirectoryService());
            }
        }
    }

    @Deployment
    public static WebArchive createDeployment() throws Exception {
        File[] libs = Maven.configureResolverViaPlugin().resolve(
                "org.apache.directory.api:api-ldap-codec-core", 
                "org.apache.directory.api:api-ldap-extras-util",
                "org.apache.directory.api:api-ldap-codec-standalone")
                .withTransitivity().asFile();

        final WebArchive archive = ShrinkWrap.create(WebArchive.class, "camel-ldap-tests.war");
        archive.addClasses(SpringLdapContextSource.class, AvailablePortFinder.class);
        archive.addAsResource("spring/ldap/producer-camel-context.xml");
        archive.addAsLibraries(libs);
        return archive;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLdapRouteStandard() throws Exception {

        int ldapPort = Integer.parseInt(AvailablePortFinder.readServerData("ldap-port"));
        SimpleRegistry reg = new SimpleRegistry();
        reg.put("localhost:" + ldapPort, getWiredContext(ldapPort));
        
        CamelContext camelctx = new DefaultCamelContext(reg);
        camelctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start").to("ldap:localhost:" + ldapPort + "?base=ou=system");
            }
        });
        
        camelctx.start();
        try {
            ProducerTemplate template = camelctx.createProducerTemplate();
            Collection<SearchResult> searchResults = template.requestBody("direct:start", "(!(ou=test1))", Collection.class);
            Assert.assertNotNull(searchResults);

            Assert.assertFalse(containsResult(searchResults, "uid=test1,ou=test,ou=system"));
            Assert.assertTrue(containsResult(searchResults, "uid=test2,ou=test,ou=system"));
            Assert.assertTrue(containsResult(searchResults, "uid=testNoOU,ou=test,ou=system"));
            Assert.assertTrue(containsResult(searchResults, "uid=tcruise,ou=actors,ou=system"));
        } finally {
            camelctx.stop();
        }
    }

    private boolean containsResult(Collection<SearchResult> results, String dn) {
        for (SearchResult result : results) {
            if (result.getNameInNamespace().equals(dn)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCamelSpringLdapRoute() throws Exception {

        SpringCamelContext camelctx = (SpringCamelContext) contextRegistry.getCamelContext("camel");
        Assert.assertNotNull(camelctx);
        
        Map<String, String> map = new HashMap<>();
        map.put("filter", "(!(ou=test1))");
        map.put("dn", "ou=system");
        
        ProducerTemplate template = camelctx.createProducerTemplate();
        List<BasicAttributes> searchResults = template.requestBody("direct:start", map, List.class);
        Assert.assertNotNull(searchResults);
        Assert.assertTrue(searchResults.size() > 0);
    }

    private LdapContext getWiredContext(int port) throws Exception {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, "ldap://" + InetAddress.getLocalHost().getHostName() + ":" + port);
        env.put(Context.SECURITY_PRINCIPAL, ServerDNConstants.ADMIN_SYSTEM_DN);
        env.put(Context.SECURITY_CREDENTIALS, "secret");
        LdapApiService ldapApiService = new StandaloneLdapApiService();
        return new InitialLdapContext(env, JndiUtils.toJndiControls(ldapApiService));
    }
}