/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jbpm.persistence.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.transaction.UserTransaction;

import org.drools.core.base.MapGlobalResolver;
import org.drools.core.impl.EnvironmentFactory;
import org.drools.core.impl.KnowledgeBaseFactory;
import org.h2.tools.DeleteDbFiles;
import org.h2.tools.Server;
import org.jbpm.process.instance.event.DefaultSignalManagerFactory;
import org.jbpm.process.instance.impl.DefaultProcessInstanceManagerFactory;
import org.kie.test.util.db.PoolingDataSourceWrapper;
import org.junit.Assert;
import org.kie.api.KieBase;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.internal.runtime.conf.ForceEagerActivationOption;
import org.kie.test.util.db.DataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.kie.api.runtime.EnvironmentName.ENTITY_MANAGER_FACTORY;
import static org.kie.api.runtime.EnvironmentName.GLOBALS;
import static org.kie.api.runtime.EnvironmentName.TRANSACTION;
import static org.kie.api.runtime.EnvironmentName.TRANSACTION_MANAGER;

public class PersistenceUtil {

    private static final Logger logger = LoggerFactory.getLogger( PersistenceUtil.class );

    // Persistence and data source constants
    public static final String DROOLS_PERSISTENCE_UNIT_NAME = "org.drools.persistence.jpa";
    public static final String DROOLS_LOCAL_PERSISTENCE_UNIT_NAME = "org.drools.persistence.jpa.local";
    public static final String JBPM_PERSISTENCE_UNIT_NAME = "org.jbpm.persistence.jpa";
    public static final String JBPM_LOCAL_PERSISTENCE_UNIT_NAME = "org.jbpm.persistence.jpa.local";
        
    protected static final String DATASOURCE_PROPERTIES = "/datasource.properties";
    
    private static TestH2Server h2Server = new TestH2Server();
    
    private static Properties defaultProperties = null;
   
    // Setup and marshalling setup constants
    public static String DATASOURCE = "org.droolsjbpm.persistence.datasource";

    /**
     * @see #setupWithPoolingDataSource(String, String)
     * @param persistenceUnitName The name of the persistence unit to be used.
     * @return test context
     */
    public static HashMap<String, Object> setupWithPoolingDataSource(String persistenceUnitName) {
        return setupWithPoolingDataSource(persistenceUnitName, "jdbc/testDS1");
    }
    
    /**
     * This method does all of the setup for the test and returns a HashMap
     * containing the persistence objects that the test might need.
     * 
     * @param persistenceUnitName
     *            The name of the persistence unit used by the test.
     * @return HashMap<String Object> with persistence objects, such as the
     *         EntityManagerFactory and DataSource
     */
    public static HashMap<String, Object> setupWithPoolingDataSource(final String persistenceUnitName, String dataSourceName) {
        HashMap<String, Object> context = new HashMap<String, Object>();

        // set the right jdbc url
        Properties dsProps = getDatasourceProperties();
        startH2TcpServer(dsProps);

        // Setup the datasource
        PoolingDataSourceWrapper ds1 = setupPoolingDataSource(dsProps, dataSourceName);
        context.put(DATASOURCE, ds1);

        // Setup persistence
        EntityManagerFactory emf = Persistence.createEntityManagerFactory(persistenceUnitName);
        context.put(ENTITY_MANAGER_FACTORY, emf);

        return context;
    }

    /**
     * This method starts H2 database server (tcp).
     * 
     * @param datasourceProperties
     *            The properties used to setup the data source.
     */
    public static void startH2TcpServer(Properties datasourceProperties) {
        String jdbcUrl = datasourceProperties.getProperty("url");
        if (jdbcUrl != null && jdbcUrl.matches("jdbc:h2:tcp:.*")) {
            h2Server.start();
        }
    }

    /**
     * This method should be called in the @After method of a test to clean up
     * the persistence unit and datasource.
     * 
     * @param context
     *            A HashMap generated by
     *            {@link org.drools.persistence.util.PersistenceUtil setupWithPoolingDataSource(String)}
     * 
     */
    public static void cleanUp(Map<String, Object> context) {
        if (context != null) {
            
            Object emfObject = context.remove(ENTITY_MANAGER_FACTORY);
            if (emfObject != null) {
                try {
                    EntityManagerFactory emf = (EntityManagerFactory) emfObject;
                    emf.close();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            Object ds1Object = context.remove(DATASOURCE);
            if (ds1Object != null) {
                try {
                    PoolingDataSourceWrapper ds1 = (PoolingDataSourceWrapper) ds1Object;
                    ds1.close();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            
        }
        
    }
    
    /**
     * This method uses the "jdbc/testDS1" datasource, which is the default.
     * @param dsProps The properties used to setup the data source.
     * @return a PoolingDataSourceWrapper
     */
    public static PoolingDataSourceWrapper setupPoolingDataSource(Properties dsProps) {
       return setupPoolingDataSource(dsProps, "jdbc/testDS1");
    }
    
    /**
     * This sets up a PoolingDataSourceWrapper.
     * 
     * @return PoolingDataSourceWrapper that has been set up but _not_ initialized.
     */
    public static PoolingDataSourceWrapper setupPoolingDataSource(Properties dsProps, String datasourceName) {
        return DataSourceFactory.setupPoolingDataSource(datasourceName, dsProps);
    }

    /**
     * Return the default database/datasource properties - These properties use
     * an in-memory H2 database
     * 
     * This is used when the developer is somehow running the tests but
     * bypassing the maven filtering that's been turned on in the pom.
     * 
     * @return Properties containing the default properties
     */
    private static Properties getDefaultProperties() {
        if (defaultProperties == null) {
            String[] keyArr = { 
                    "serverName", "portNumber", "databaseName", 
                    "url", 
                    "user", "password", 
                    "driverClassName",
                    "className", 
                    "maxPoolSize", 
                    "allowLocalTransactions" };
            String[] defaultPropArr = { 
                    "", "", "", 
                    "jdbc:h2:tcp://localhost/target/jbpm-test", 
                    "sa", "", 
                    "org.h2.Driver",
                    "org.h2.jdbcx.JdbcDataSource", 
                    "16", 
                    "true" };
            Assert.assertTrue("Unequal number of keys for default properties", keyArr.length == defaultPropArr.length);
            defaultProperties = new Properties();
            for (int i = 0; i < keyArr.length; ++i) {
                defaultProperties.put(keyArr[i], defaultPropArr[i]);
            }
        }

        return defaultProperties;
    }

    /**
     * This reads in the (maven filtered) datasource properties from the test
     * resource directory.
     * 
     * @return Properties containing the datasource properties.
     */
    public static Properties getDatasourceProperties() { 
        String propertiesNotFoundMessage = "Unable to load datasource properties [" + DATASOURCE_PROPERTIES + "]";
        boolean propertiesNotFound = false;

        // Central place to set additional H2 properties
        System.setProperty("h2.lobInDatabase", "true");
        
        Properties props = new Properties();
        try (InputStream propsInputStream = PersistenceUtil.class.getResourceAsStream(DATASOURCE_PROPERTIES)){
            assertNotNull(propertiesNotFoundMessage, propsInputStream);
            props.load(propsInputStream);
        } catch (IOException ioe) {
            propertiesNotFound = true;
            logger.warn("Unable to find properties, using default H2 properties: {}", ioe.getMessage());
            logger.warn("Stacktrace:", ioe);
        }

        String password = props.getProperty("password");
        if ("${maven.jdbc.password}".equals(password) || propertiesNotFound) {
            props = getDefaultProperties();
        }

        return props;
    }

    /**
     * This method returns whether or not transactions should be used when
     * dealing with the SessionInfo object (or any other persisted entity that
     * contains @Lob's )
     * 
     * @return boolean Whether or not to use transactions
     */
    public static boolean useTransactions() {
        boolean useTransactions = false;
        String databaseDriverClassName = getDatasourceProperties().getProperty("driverClassName");

        // Postgresql has a "Large Object" api which REQUIRES the use of transactions
        //  since @Lob/byte array is actually stored in multiple tables.
        if (databaseDriverClassName.startsWith("org.postgresql") || databaseDriverClassName.startsWith("com.edb")) {
            useTransactions = true;
        }
        return useTransactions;
    }

    /**
     * Reflection method when doing ugly hacks in tests.
     * 
     * @param fieldname
     *            The name of the field to be retrieved.
     * @param source
     *            The object containing the field to be retrieved.
     * @return The value (object instance) stored in the field requested from
     *         the given source object.
     */
    public static Object getValueOfField(String fieldname, Object source) {
        String sourceClassName = source.getClass().getSimpleName();
    
        Field field = null;
        try {
            field = source.getClass().getDeclaredField(fieldname);
            field.setAccessible(true);
        } catch (SecurityException e) {
            fail("Unable to retrieve " + fieldname + " field from " + sourceClassName + ": " + e.getCause());
        } catch (NoSuchFieldException e) {
            fail("Unable to retrieve " + fieldname + " field from " + sourceClassName + ": " + e.getCause());
        }
    
        assertNotNull("." + fieldname + " field is null!?!", field);
        Object fieldValue = null;
        try {
            fieldValue = field.get(source);
        } catch (IllegalArgumentException e) {
            fail("Unable to retrieve value of " + fieldname + " from " + sourceClassName + ": " + e.getCause());
        } catch (IllegalAccessException e) {
            fail("Unable to retrieve value of " + fieldname + " from " + sourceClassName + ": " + e.getCause());
        }
        return fieldValue;
    }

    public static Environment createEnvironment(Map<String, Object> context) {
        Environment env = EnvironmentFactory.newEnvironment();
        
        UserTransaction ut = (UserTransaction) context.get(TRANSACTION);
        if( ut != null ) { 
            env.set( TRANSACTION, ut);
        }
        
        env.set( ENTITY_MANAGER_FACTORY, context.get(ENTITY_MANAGER_FACTORY) );
        env.set( TRANSACTION_MANAGER, com.arjuna.ats.jta.TransactionManager.transactionManager() );
        env.set( GLOBALS, new MapGlobalResolver() );
        
        return env;
    }
    
   /**
    * An class responsible for starting and stopping the H2 database (tcp)
    * server
    */
   private static class TestH2Server {
       private Server realH2Server;

       public void start() {
           if (realH2Server == null || !realH2Server.isRunning(false)) {
               try {
                   DeleteDbFiles.execute("", "JPADroolsFlow", true);
                   realH2Server = Server.createTcpServer(new String[0]);
                   realH2Server.start();
               } catch (SQLException e) {
                   throw new RuntimeException("can't start h2 server db", e);
               }
           }
       }

       @Override
       protected void finalize() throws Throwable {
           if (realH2Server != null) {
               realH2Server.stop();
           }
           DeleteDbFiles.execute("", "target/jbpm-test", true);
           super.finalize();
       }

   }

   public static KieSession createKieSessionFromKBase(KieBase kbase, Map<String, Object> context) {
       Properties defaultProps = new Properties();
       defaultProps.setProperty("drools.processSignalManagerFactory",
               DefaultSignalManagerFactory.class.getName());
       defaultProps.setProperty("drools.processInstanceManagerFactory",
               DefaultProcessInstanceManagerFactory.class.getName());
       KieSessionConfiguration ksconf = KnowledgeBaseFactory.newKnowledgeSessionConfiguration(defaultProps);
       ksconf.setOption(ForceEagerActivationOption.YES);
               
       return kbase.newKieSession(ksconf, createEnvironment(context));
   }
   
}
