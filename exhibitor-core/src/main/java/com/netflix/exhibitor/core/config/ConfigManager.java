/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.exhibitor.core.config;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.Activity;
import com.netflix.exhibitor.core.activity.QueueGroups;
import com.netflix.exhibitor.core.activity.RepeatingActivity;
import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigManager implements Closeable
{
    private final Exhibitor exhibitor;
    private final ConfigProvider provider;
    private final RepeatingActivity repeatingActivity;
    private final AtomicReference<LoadedInstanceConfig> config = new AtomicReference<LoadedInstanceConfig>();
    private final Set<ConfigListener> configListeners = Sets.newSetFromMap(Maps.<ConfigListener, Boolean>newConcurrentMap());

    public ConfigManager(Exhibitor exhibitor, ConfigProvider provider, int checkMs) throws Exception
    {
        this.exhibitor = exhibitor;
        this.provider = provider;

        Activity    activity = new Activity()
        {
            @Override
            public void completed(boolean wasSuccessful)
            {
            }

            @Override
            public Boolean call() throws Exception
            {
                doWork();
                return true;
            }
        };
        repeatingActivity = new RepeatingActivity(exhibitor.getLog(), exhibitor.getActivityQueue(), QueueGroups.MAIN, activity, checkMs);

        config.set(provider.loadConfig());
    }

    public void   start()
    {
        repeatingActivity.start();
    }

    @Override
    public void close() throws IOException
    {
        repeatingActivity.close();
    }

    public InstanceConfig getConfig()
    {
        return config.get().getConfig().getConfigForThisInstance(exhibitor.getThisJVMHostname());
    }

    public boolean              isRolling()
    {
        return config.get().getConfig().isRolling();
    }

    /**
     * Add a listener for config changes
     *
     * @param listener listener
     */
    public void addConfigListener(ConfigListener listener)
    {
        configListeners.add(listener);
    }

    public synchronized boolean startRollingConfig(final InstanceConfig newConfig) throws Exception
    {
        // TODO - reject if in rolling config change

        final InstanceConfig    currentConfig = config.get().getConfig().getRootConfig();
        ConfigCollection        newCollection = new ConfigCollectionImpl(currentConfig, newConfig);
        return internalUpdateConfig(newCollection);
    }

    public synchronized boolean updateConfig(final InstanceConfig newConfig) throws Exception
    {
        // TODO - reject if in rolling config change

        ConfigCollection        newCollection = new ConfigCollectionImpl(newConfig, null);
        return internalUpdateConfig(newCollection);
    }

    private boolean internalUpdateConfig(ConfigCollection newCollection) throws Exception
    {
        LoadedInstanceConfig updated = provider.storeConfig(newCollection, config.get().getLastModified());
        if ( updated != null )
        {
            config.set(updated);
            notifyListeners();
            return true;
        }

        return false;
    }

    private synchronized void notifyListeners()
    {
        for ( ConfigListener listener : configListeners )
        {
            listener.configUpdated();
        }
    }

    private synchronized void doWork() throws Exception
    {
        LoadedInstanceConfig    newConfig = provider.loadConfig();
        if ( newConfig.getLastModified() != config.get().getLastModified() )
        {
            config.set(newConfig);
            notifyListeners();
        }
    }

}
