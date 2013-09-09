/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.server.initialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.metamx.common.ISE;
import com.metamx.common.logger.Logger;
import io.druid.guice.DruidGuiceExtensions;
import io.druid.guice.DruidSecondaryModule;
import io.druid.guice.JsonConfigProvider;
import io.druid.guice.annotations.Json;
import io.druid.guice.annotations.Smile;
import io.druid.initialization.DruidModule;
import io.druid.jackson.JacksonModule;
import io.tesla.aether.TeslaAether;
import io.tesla.aether.internal.DefaultTeslaAether;
import org.eclipse.aether.artifact.Artifact;

import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 */
public class Initialization
{
  private static final Logger log = new Logger(Initialization.class);

  private static final List<String> exclusions = Arrays.asList(
      "io.druid",
      "com.metamx.druid"
  );


  public static Injector makeInjector(final Object... modules)
  {
    final Injector baseInjector = Guice.createInjector(
        new DruidGuiceExtensions(),
        new JacksonModule(),
        new PropertiesModule("runtime.properties"),
        new ConfigModule(),
        new Module()
        {
          @Override
          public void configure(Binder binder)
          {
            binder.bind(DruidSecondaryModule.class);
            JsonConfigProvider.bind(binder, "druid.extensions", ExtensionsConfig.class);

            for (Object module : modules) {
              if (module instanceof Class) {
                binder.bind((Class) module);
              }
            }
          }
        }
    );

    ModuleList actualModules = new ModuleList(baseInjector);
    actualModules.addModule(DruidSecondaryModule.class);
    for (Object module : modules) {
      actualModules.addModule(module);
    }

    addExtensionModules(baseInjector.getInstance(ExtensionsConfig.class), actualModules);

    return Guice.createInjector(actualModules.getModules());
  }

  private static void addExtensionModules(ExtensionsConfig config, ModuleList actualModules)
  {
    final TeslaAether aether = getAetherClient(config);

    for (String coordinate : config.getCoordinates()) {
      log.info("Loading extension[%s]", coordinate);
      try {
        final List<Artifact> artifacts = aether.resolveArtifacts(coordinate);
        List<URL> urls = Lists.newArrayListWithExpectedSize(artifacts.size());
        for (Artifact artifact : artifacts) {
          if (!exclusions.contains(artifact.getGroupId())) {
            urls.add(artifact.getFile().toURI().toURL());
          }
          else {
            log.debug("Skipped Artifact[%s]", artifact);
          }
        }

        for (URL url : urls) {
          log.debug("Added URL[%s]", url);
        }

        ClassLoader loader = new URLClassLoader(
            urls.toArray(new URL[urls.size()]), Initialization.class.getClassLoader()
        );

        final ServiceLoader<DruidModule> serviceLoader = ServiceLoader.load(DruidModule.class, loader);

        for (DruidModule module : serviceLoader) {
          log.info("Adding extension module[%s]", module.getClass());
          actualModules.addModule(module);
        }
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  private static DefaultTeslaAether getAetherClient(ExtensionsConfig config)
  {
    /*
    DefaultTeslaAether logs a bunch of stuff to System.out, which is annoying.  We choose to disable that
    unless debug logging is turned on.  "Disabling" it, however, is kinda bass-ackwards.  We copy out a reference
    to the current System.out, and set System.out to a noop output stream.  Then after DefaultTeslaAether has pulled
    The reference we swap things back.

    This has implications for other things that are running in parallel to this.  Namely, if anything else also grabs
    a reference to System.out or tries to log to it while we have things adjusted like this, then they will also log
    to nothingness.  Fortunately, the code that calls this is single-threaded and shouldn't hopefully be running
    alongside anything else that's grabbing System.out.  But who knows.
    */
    if (log.isTraceEnabled() || log.isDebugEnabled()) {
      return new DefaultTeslaAether(config.getLocalRepository(), config.getRemoteRepositories());
    }

    PrintStream oldOut = System.out;
    try {
      System.setOut(new PrintStream(ByteStreams.nullOutputStream()));
      return new DefaultTeslaAether(config.getLocalRepository(), config.getRemoteRepositories());
    }
    finally {
      System.setOut(oldOut);
    }
  }

  private static class ModuleList
  {
    private final Injector baseInjector;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper smileMapper;
    private final List<Module> modules;

    public ModuleList(Injector baseInjector) {
      this.baseInjector = baseInjector;
      this.jsonMapper = baseInjector.getInstance(Key.get(ObjectMapper.class, Json.class));
      this.smileMapper = baseInjector.getInstance(Key.get(ObjectMapper.class, Smile.class));
      this.modules = Lists.newArrayList();
    }

    private List<Module> getModules()
    {
      return Collections.unmodifiableList(modules);
    }

    public void addModule(Object input)
    {
      if (input instanceof DruidModule) {
        baseInjector.injectMembers(input);
        modules.add(registerJacksonModules(((DruidModule) input)));
      }
      else if (input instanceof Module) {
        baseInjector.injectMembers(input);
        modules.add((Module) input);
      }
      else if (input instanceof Class) {
        if (DruidModule.class.isAssignableFrom((Class) input)) {
          modules.add(registerJacksonModules(baseInjector.getInstance((Class<? extends DruidModule>) input)));
        }
        else if (Module.class.isAssignableFrom((Class) input)) {
          modules.add(baseInjector.getInstance((Class<? extends Module>) input));
          return;
        }
        else {
          throw new ISE("Class[%s] does not implement %s", input.getClass(), Module.class);
        }
      }
      else {
        throw new ISE("Unknown module type[%s]", input.getClass());
      }
    }

    private DruidModule registerJacksonModules(DruidModule module)
    {
      for (com.fasterxml.jackson.databind.Module jacksonModule : module.getJacksonModules()) {
        jsonMapper.registerModule(jacksonModule);
        smileMapper.registerModule(jacksonModule);
      }
      return module;
    }
  }

  }