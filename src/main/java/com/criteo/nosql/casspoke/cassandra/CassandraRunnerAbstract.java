package com.criteo.nosql.casspoke.cassandra;

import com.criteo.nosql.casspoke.config.Config;
import com.criteo.nosql.casspoke.discovery.Consul;
import com.criteo.nosql.casspoke.discovery.IDiscovery;
import com.criteo.nosql.casspoke.discovery.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;

public abstract class CassandraRunnerAbstract implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CassandraRunnerAbstract.class);

    private final Config cfg;
    private final IDiscovery discovery;
    private final long measurementPeriodInMs;
    private final long refreshDiscoveryPeriodInMs;

    protected Map<Service, Set<InetSocketAddress>> services;
    protected Map<Service, Optional<CassandraMonitor>> monitors;
    protected Map<Service, CassandraMetrics> metrics;

    public CassandraRunnerAbstract(Config cfg, IDiscovery discovery) {
        this.cfg = cfg;

        this.discovery = discovery;
        this.measurementPeriodInMs = Long.parseLong(cfg.getApp().getOrDefault("measurementPeriodInSec", "30")) * 1000L;
        this.refreshDiscoveryPeriodInMs = Long.parseLong(cfg.getApp().getOrDefault("refreshDiscoveryPeriodInSec", "300")) * 1000L;

        this.services = Collections.emptyMap();
        this.monitors = Collections.emptyMap();
        this.metrics = Collections.emptyMap();
    }

    @Override
    public void run() {
        final List<EVENT> evts = Arrays.asList(EVENT.UPDATE_TOPOLOGY, EVENT.WAIT, EVENT.POKE);

        for (; ; ) {
            final long start = System.currentTimeMillis();
            final EVENT evt = evts.get(0);
            dispatch_events(evt);
            final long stop = System.currentTimeMillis();
            logger.info("{} took {} ms", evt, stop - start);

            rescheduleEvent(evt, start, stop);
            Collections.sort(evts, Comparator.comparingLong(event -> event.nexTick));
        }
    }

    private void rescheduleEvent(EVENT lastEvt, long start, long stop) {
        final long duration = stop - start;
        if (duration >= measurementPeriodInMs) {
            logger.warn("Operation took longer than 1 tick, please increase tick rate if you see this message too often");
        }

        EVENT.WAIT.nexTick = start + measurementPeriodInMs - 1;
        switch (lastEvt) {
            case WAIT:
                break;

            case UPDATE_TOPOLOGY:
                lastEvt.nexTick = start + refreshDiscoveryPeriodInMs;
                break;

            case POKE:
                lastEvt.nexTick = start + measurementPeriodInMs;
                break;
        }
    }

    private void dispatch_events(EVENT evt) {
        switch (evt) {
            case WAIT:
                try {
                    Thread.sleep(Math.max(evt.nexTick - System.currentTimeMillis(), 0));
                } catch (Exception e) {
                    logger.error("thread interrupted {}", e);
                }
                break;

            case UPDATE_TOPOLOGY:
                updateTopology();
                break;

            case POKE:
                poke();
                break;
        }
    }

    public void updateTopology() {
        final Map<Service, Set<InetSocketAddress>> new_services = discovery.getServicesNodesFor(cfg.getService().tags);

        // Discovery down?
        if (new_services.isEmpty()) {
            logger.warn("Discovery sent back no service to monitor. Is it down? Check your configuration.");
            return;
        }

        // Check if topology changed
        if (IDiscovery.areServicesEquals(services, new_services)) {
            logger.trace("No topology change.");
            return;
        }

        logger.info("Topology changed. Monitors are updating...");
        // Dispose old monitors and metrics
        monitors.values().forEach(mo -> mo.ifPresent(CassandraMonitor::close));
        metrics.values().forEach(CassandraMetrics::close);

        // Create new ones
        services = new_services;

        final int timeoutInMs = cfg.getService().timeoutInSec * 1000;
        monitors = new HashMap<>(services.size());
        services.forEach((service, endPoints) -> {
            monitors.put(service,
                    CassandraMonitor.fromNodes(service, endPoints, timeoutInMs));
        });

        metrics = new HashMap<>(services.size());
        services.forEach((service, v) -> metrics.put(service, new CassandraMetrics(service)));
    }

    protected abstract void poke();

    private enum EVENT {
        UPDATE_TOPOLOGY(System.currentTimeMillis()),
        WAIT(System.currentTimeMillis()),
        POKE(System.currentTimeMillis());

        public long nexTick;

        EVENT(long nexTick) {
            this.nexTick = nexTick;
        }
    }

}
