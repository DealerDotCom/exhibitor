/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.exhibitor.servlet;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import com.google.common.io.Resources;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.ExhibitorArguments;
import com.netflix.exhibitor.core.RemoteConnectionConfiguration;
import com.netflix.exhibitor.standalone.ExhibitorCLI;
import com.netflix.exhibitor.standalone.ExhibitorCreator;
import com.netflix.exhibitor.standalone.ExhibitorCreatorExit;
import com.netflix.exhibitor.standalone.MissingConfigurationTypeException;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.client.filter.HTTPDigestAuthFilter;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ExhibitorServletContextListener implements ServletContextListener
{
    private final Logger        log = LoggerFactory.getLogger(getClass());

    private volatile Exhibitor  exhibitor;
    private volatile ExhibitorCreator exhibitorCreator;

    private static final String OUR_PREFIX = "exhibitor-";
    private static final String EXHIBITOR_PROPERTIES = "exhibitor.properties";
	private static final String SYSTEM_ARG_NAME_FORMAT_STRING = "-%s";

    @Override
    public void contextInitialized(ServletContextEvent event)
    {
        Map<String, String> argsBuilder = makeArgsBuilder();

        try
        {
            exhibitorCreator = new ExhibitorCreator(toArgsArray(argsBuilder));

            exhibitor = new Exhibitor(exhibitorCreator.getConfigProvider(), null, exhibitorCreator.getBackupProvider(), addRemoteAuth(exhibitorCreator.getBuilder(), argsBuilder.get("--" + ExhibitorCLI.REMOTE_CLIENT_AUTHORIZATION)).build());
            exhibitor.start();

            event.getServletContext().setAttribute(ExhibitorServletContextListener.class.getName(), exhibitor);
        }
        catch ( MissingConfigurationTypeException exit )
        {
            log.error("Configuration type (" + OUR_PREFIX + ExhibitorCLI.CONFIG_TYPE + ") must be specified");
            exit.getCli().logHelp(OUR_PREFIX);
            throw new RuntimeException(exit);
        }
        catch ( ExhibitorCreatorExit exit )
        {
            if ( exit.getError() != null )
            {
                log.error(exit.getError());
            }
            exit.getCli().logHelp(OUR_PREFIX);
            throw new RuntimeException(exit);
        }
        catch ( Exception e )
        {
            log.error("Trying to create Exhibitor", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event)
    {
        if ( exhibitor != null )
        {
            Closeables.closeQuietly(exhibitor);
            exhibitor = null;
        }

        if ( exhibitorCreator != null )
        {
            for ( Closeable closeable : exhibitorCreator.getCloseables() )
            {
                Closeables.closeQuietly(closeable);
            }
        }
    }

    private String[] toArgsArray(Map<String, String> argsBuilder)
    {
        List<String>        args = Lists.newArrayList();
        for ( Map.Entry<String, String> entry : argsBuilder.entrySet() )
        {
            args.add(entry.getKey());
            args.add(entry.getValue());
        }

        return args.toArray(new String[args.size()]);
    }

    private Map<String, String> makeArgsBuilder()
    {
        Map<String, String>     argsBuilder = Maps.newHashMap();

        try
        {
            URL         resource = Resources.getResource(EXHIBITOR_PROPERTIES);
            InputStream stream = resource.openStream();
            try
            {
                Properties  properties = new Properties(System.getProperties());
                properties.load(stream);
                applyProperties(argsBuilder, properties);
            }
            finally
            {
                Closeables.closeQuietly(stream);
            }
        }
        catch ( IllegalArgumentException e )
        {
            log.warn("Could not find " + EXHIBITOR_PROPERTIES);
        }
        catch ( IOException e )
        {
            log.error("Could not load " + EXHIBITOR_PROPERTIES, e);
        }

        applyProperties(argsBuilder, System.getProperties());

        return argsBuilder;
    }

    private void applyProperties(Map<String, String> argsBuilder, Properties properties)
    {
        Enumeration parameterNames = properties.propertyNames();
        while ( parameterNames.hasMoreElements() )
        {
            final String  name = String.valueOf(parameterNames.nextElement());
            if ( name.startsWith(OUR_PREFIX) )
            {
                final String value = properties.getProperty(name);
                final String argName = String.format(SYSTEM_ARG_NAME_FORMAT_STRING, name.substring(OUR_PREFIX.length()));
                argsBuilder.put(argName, value);

                log.info(String.format("Setting property %s=%s", argName, value));
            }
        }
    }

    private ExhibitorArguments.Builder addRemoteAuth(final ExhibitorArguments.Builder builder, final String remoteAuthSpec) {
		if(remoteAuthSpec != null && remoteAuthSpec.length() > 0) {
        	final String[] parts = remoteAuthSpec.split(":");
        	Preconditions.checkArgument(parts.length == 2, "Badly formed remote client authorization: {}.", remoteAuthSpec);

        	final String type = parts[0].trim();
        	final String auth = Preconditions.checkNotNull(new String(Base64.decodeBase64(parts[1].trim())), "Authentication token cannot be null.");
			final String[] authParts = auth.split(":");
			
			final String username = authParts[0].trim();
        	final String password = authParts[1].trim();

        	if ( type.equals("basic") ) {
				final ClientFilter filter = new HTTPBasicAuthFilter(username, password);
            	builder.remoteConnectionConfiguration(new RemoteConnectionConfiguration(Arrays.asList(filter)));
        	} else if ( type.equals("digest") ) {
				final ClientFilter filter = new HTTPDigestAuthFilter(username, password);
            	builder.remoteConnectionConfiguration(new RemoteConnectionConfiguration(Arrays.asList(filter)));
        	} else {
            	log.warn("Unknown remote client authorization type: {}.", type);
        	}
		}
		return builder;
    }
}
