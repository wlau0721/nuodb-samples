/* Copyright (c) 2013-2015 NuoDB, Inc. */

package com.nuodb.storefront.service.storefront;

import java.net.URI;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.ws.rs.core.MediaType;

import org.apache.log4j.Logger;

import com.nuodb.storefront.StorefrontApp;
import com.nuodb.storefront.StorefrontFactory;
import com.nuodb.storefront.dal.IStorefrontDao;
import com.nuodb.storefront.dal.StorefrontDao;
import com.nuodb.storefront.dal.TransactionType;
import com.nuodb.storefront.exception.ApiException;
import com.nuodb.storefront.model.dto.DbConnInfo;
import com.nuodb.storefront.model.dto.DbRegionInfo;
import com.nuodb.storefront.model.dto.RegionStats;
import com.nuodb.storefront.model.entity.AppInstance;
import com.nuodb.storefront.service.IHeartbeatService;
import com.nuodb.storefront.service.IStorefrontPeerService;
import com.nuodb.storefront.service.simulator.SimulatorService;
import com.nuodb.storefront.util.PerformanceUtil;
import com.sun.jersey.api.client.Client;

public class HeartbeatService implements IHeartbeatService, IStorefrontPeerService {
    private static final Logger s_log = Logger.getLogger(SimulatorService.class.getName());
    private int secondsUntilNextPurge = 0;
    private int consecutiveFailureCount = 0;
    private Map<String, Set<URI>> wakeList = new HashMap<String, Set<URI>>();

    static {
        StorefrontDao.registerTransactionNames(new String[] { "sendHeartbeat" });
    }

    public HeartbeatService() {
    }

    @Override
    public void run() {
        try {
            final IStorefrontDao dao = StorefrontFactory.createStorefrontDao();
            dao.runTransaction(TransactionType.READ_WRITE, "sendHeartbeat", new Runnable() {
                @Override
                public void run() {
                    Calendar now = Calendar.getInstance();
                    AppInstance appInstance = StorefrontApp.APP_INSTANCE;
                    secondsUntilNextPurge -= StorefrontApp.HEARTBEAT_INTERVAL_SEC;

                    if (appInstance.getFirstHeartbeat() == null) {
                        appInstance.setFirstHeartbeat(now);
                        appInstance.setLastApiActivity(now);
                    }

                    // Send the heartbeat with the latest "last heartbeat time"
                    appInstance.setCpuUtilization(PerformanceUtil.getAvgCpuUtilization());
                    appInstance.setLastHeartbeat(now);
                    if (!appInstance.getRegionOverride()) {
                        DbRegionInfo region = dao.getCurrentDbNodeRegion();
                        appInstance.setRegion(region.regionName);
                        appInstance.setNodeId(region.nodeId);
                    }
                    dao.save(StorefrontApp.APP_INSTANCE); // this will create or update as appropriate

                    // If enough time has elapsed, also delete rows of instances that are no longer sending heartbeats
                    if (secondsUntilNextPurge <= 0) {
                        Calendar maxLastHeartbeat = Calendar.getInstance();
                        maxLastHeartbeat.add(Calendar.SECOND, -StorefrontApp.MIN_INSTANCE_PURGE_AGE_SEC);
                        dao.deleteDeadAppInstances(maxLastHeartbeat);
                        secondsUntilNextPurge = StorefrontApp.PURGE_FREQUENCY_SEC;
                    }

                    // If interactive user has left the app, shut down any active workloads
                    Calendar idleThreshold = Calendar.getInstance();
                    idleThreshold.add(Calendar.SECOND, -StorefrontApp.STOP_USERS_AFTER_IDLE_UI_SEC);
                    if (appInstance.getStopUsersWhenIdle() && appInstance.getLastApiActivity().before(idleThreshold)) {
                        // Don't do any heavy lifting if there are no simulated workloads in progress
                        int activeWorkerCount = StorefrontFactory.getSimulatorService().getActiveWorkerLimit();
                        if (activeWorkerCount > 0) {
                            // Check for idleness across *all* instances
                            if (dao.getActiveAppInstanceCount(idleThreshold) == 0) {
                                s_log.info("Stopping all " + activeWorkerCount + " simulated users due to idle app instances.");
                                StorefrontFactory.getSimulatorService().stopAll();
                            }
                        }
                    } else {
                        // We're still active, so if there are Storefronts to wake up, let's do it
                        wakeStorefronts();
                    }

                    consecutiveFailureCount = 0;
                }
            });
        } catch (Exception e) {
            if (++consecutiveFailureCount == 1) {
                s_log.error("Unable to send heartbeat", e);
            }
        }
    }

    @Override
    public void asyncWakeStorefrontsInOtherRegions() {
        // Assume no regions are covered
        Collection<RegionStats> regions = StorefrontFactory.getDbApi().getRegionStats();
        Map<String, RegionStats> missingRegions = new HashMap<String, RegionStats>();
        for (RegionStats region : regions) {
            if (region.usedHostCount > 0) {
                missingRegions.put(region.region, region);
            }
        }

        if (regions.size() <= 1) {
            // When there's only 1 region, we're in it -- so there's no work to do
            return;
        }

        // Eliminate regions that are covered by existing active instances
        List<AppInstance> instances = StorefrontFactory.createStorefrontService().getAppInstances(true);
        for (AppInstance instance : instances) {
            missingRegions.remove(instance.getRegion());
        }

        // Queue up candidate URLs of storefronts in regions that are not covered
        synchronized (wakeList) {
            // Discard prior data. We've now got the latest across all regions.
            wakeList.clear();

            for (RegionStats region : missingRegions.values()) {
                // Put the URIs in a *sorted* set so all active Storefronts hit these in a deterministic order.
                // Otherwise they may wake multiple Storefronts in a region, which isn't bad but unnecessary.
                wakeList.put(region.region, new TreeSet<URI>(region.usedHostUrls));
            }
        }
    }

    protected void wakeStorefronts() {
        HashMap<String, Set<URI>> wakeListCopy;
        synchronized (wakeList) {
            if (wakeList.isEmpty()) {
                return;
            }
            wakeListCopy = new HashMap<String, Set<URI>>(wakeList);
            wakeList.clear();
        }

        Client client = StorefrontFactory.createApiClient();
        for (Map.Entry<String, Set<URI>> entry : wakeListCopy.entrySet()) {
            String region = entry.getKey();
            for (URI peer : entry.getValue()) {
                // FIXME:  Assume same port and context path as this instance
                String peerUrl = peer + ":" + StorefrontApp.DEFAULT_PORT + "/storefront/api/app-instances/sync";

                try {                    
                    // FIXME: Share the IP and port with other storefront
                    
                    client.resource(peerUrl)
                            .type(MediaType.APPLICATION_JSON)
                            .put(DbConnInfo.class, StorefrontFactory.getDbConnInfo());
                    s_log.info("Successfully contacted peer Storefront at [" + peer + "] in the " + region + " region.");

                    // Success.  We're done in this region.
                    break;
                } catch (Exception e) {
                    ApiException ae = ApiException.toApiException(e);
                    s_log.warn("Unable to contact peer Storefront [" + peer + "] in the " + region + " region: " + ae.getMessage());
                }
            }
        }
    }
}
