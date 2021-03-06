/**
 * Copyright 2013 Technische Universitat Wien (TUW), Distributed Systems Group
 * E184
 *
 * This work was partially supported by the European Commission in terms of the
 * CELAR FP7 project (FP7-ICT-2011-8 \#317790)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package at.ac.tuwien.dsg.mela.dataservice;

import at.ac.tuwien.dsg.mela.common.configuration.metricComposition.CompositionOperation;
import at.ac.tuwien.dsg.mela.common.configuration.metricComposition.CompositionRule;
import at.ac.tuwien.dsg.mela.common.configuration.metricComposition.CompositionRulesConfiguration;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.Metric;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.MonitoredElement;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.ServiceMonitoringSnapshot;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.dataCollection.AbstractDataAccess;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.dataCollection.AbstractDataSource;
import at.ac.tuwien.dsg.mela.common.requirements.MetricFilter;
import at.ac.tuwien.dsg.mela.common.requirements.Requirements;
import at.ac.tuwien.dsg.mela.dataservice.aggregation.DataAggregationEngine;
import at.ac.tuwien.dsg.mela.dataservice.config.ConfigurationXMLRepresentation;
import at.ac.tuwien.dsg.mela.dataservice.persistence.PersistenceSQLAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daniel Moldovan E-Mail: d.moldovan@dsg.tuwien.ac.at *
 */
@Service
@DependsOn("persistenceSQLAccess")
public class DataCollectionService {

    static final Logger log = LoggerFactory.getLogger(DataCollectionService.class);

    @Value("#{${dataaccess.automaticstructuredetection} ? @autoStructureDetectionDataAccess : @defaultDataAccess}")
    private AbstractDataAccess dataAccess;

    private Requirements requirements;

    private CompositionRulesConfiguration compositionRulesConfiguration;

    private MonitoredElement serviceConfiguration;

    // used for data Aggregation over time
    private List<ServiceMonitoringSnapshot> historicalMonitoringData;

    // used if someone wants freshest data
    // private ServiceMonitoringSnapshot latestMonitoringData;
    // interval at which RAW monitoring data is collected
    @Value("${monitoring.polling.interval:5}")
    private int monitoringIntervalInSeconds;

    // interval over which raw monitoring data is aggregated.
    // example: for monitoringIntervalInSeconds at 5 seconds, and aggregation at
    // 30,
    // means 6 monitoring snapshots are aggregated into 1
    @Value("${monitoring.aggregation.windowsize:2}")
    private int aggregationWindowsCount;

    private Timer monitoringTimer;

    // holding MonitoredElement name, and Actions Name
    private Map<MonitoredElement, List<String>> actionsInExecution;

    @Autowired
    private DataAggregationEngine instantMonitoringDataEnrichmentEngine;

    @Autowired
    private PersistenceSQLAccess persistenceSQLAccess;

    @Autowired
    private ApplicationContext context;


    // used in monitoring
    private TimerTask task = new TimerTask() {
        @Override
        public void run() {
        }
    };

    @PostConstruct
    public void init() {
        log.debug("Initializing DataCollectionService");
        historicalMonitoringData = new ArrayList<ServiceMonitoringSnapshot>();
        monitoringTimer = new Timer();
        actionsInExecution = new ConcurrentHashMap<MonitoredElement, List<String>>();

        if ((monitoringIntervalInSeconds / aggregationWindowsCount) == 0) {
            aggregationWindowsCount = monitoringIntervalInSeconds;
        }

        // get latest config
        ConfigurationXMLRepresentation configurationXMLRepresentation = persistenceSQLAccess.getLatestConfiguration();
        serviceConfiguration = configurationXMLRepresentation.getServiceConfiguration();
        persistenceSQLAccess.setMonitoringId(serviceConfiguration.getId());
        persistenceSQLAccess.writeMonitoringSequenceId(serviceConfiguration.getId());
        setCompositionRulesConfiguration(configurationXMLRepresentation.getCompositionRulesConfiguration());
        requirements = configurationXMLRepresentation.getRequirements();



        startMonitoring();
    }

    public synchronized MonitoredElement getServiceConfiguration() {
        return serviceConfiguration;
    }

    public synchronized void addExecutingAction(String targetEntityID, List<String> actionName) {
        MonitoredElement element = new MonitoredElement(targetEntityID);
        actionsInExecution.put(element, actionName);
    }

    public synchronized void setConfiguration(ConfigurationXMLRepresentation configurationXMLRepresentation) {
        serviceConfiguration = configurationXMLRepresentation.getServiceConfiguration();
        setCompositionRulesConfiguration(configurationXMLRepresentation.getCompositionRulesConfiguration());

        requirements = configurationXMLRepresentation.getRequirements();
        monitoringTimer.cancel();
        monitoringTimer.purge();
        startMonitoring();
    }

    public synchronized void removeExecutingAction(String targetEntityID, List<String> actionName) {
        MonitoredElement element = new MonitoredElement(targetEntityID);
        if (actionsInExecution.containsKey(element)) {
            actionsInExecution.get(element).removeAll(actionName);
        }
    }

    public synchronized void setServiceConfiguration(MonitoredElement serviceConfiguration) {
        this.serviceConfiguration = serviceConfiguration;
        monitoringTimer.cancel();
        monitoringTimer.purge();
        /*try {
            persistenceSQLAccess.closeConnection();
        } catch (SQLException e) {
            Logger.getLogger(this.getClass()).log(Level.ERROR, null, e);
        }*/
        /*persistenceSQLAccess = new PersistenceSQLAccess("mela", "mela", Configuration.getDataServiceIP(), Configuration.getDataServicePort(),
                serviceConfiguration.getId());*/ // todo refresh persistence context here to reload data

        startMonitoring();
    }

    public synchronized void setRequirements(Requirements requirements) {
        this.requirements = requirements;
    }

    // actually removes all VMs and Virtual Clusters from the ServiceUnit and
    // adds new ones.
    public synchronized void updateServiceConfiguration(MonitoredElement serviceConfiguration) {
        // extract all ServiceUnit level monitored elements from both services,
        // and replace their children
        Map<MonitoredElement, MonitoredElement> serviceUnits = new HashMap<MonitoredElement, MonitoredElement>();
        for (MonitoredElement element : this.serviceConfiguration) {
            if (element.getLevel().equals(MonitoredElement.MonitoredElementLevel.SERVICE_UNIT)) {
                // remove element's children
                element.getContainedElements().clear();
                serviceUnits.put(element, element);
            }
        }

        // go trough the new service, and for each Service Unit, add its
        // children (containing both Virtual Machines and Virtual Clusters) to
        // the original service
        for (MonitoredElement element : serviceConfiguration) {
            if (serviceUnits.containsKey(element)) {
                // bad practice. breaks encapsulation
                serviceUnits.get(element).getContainedElements().addAll(element.getContainedElements());
            }
        }

    }

    public synchronized void setCompositionRulesConfiguration(CompositionRulesConfiguration compositionRulesConfiguration) {
        this.compositionRulesConfiguration = compositionRulesConfiguration;
    }

    public synchronized ServiceMonitoringSnapshot getRawMonitoringData() {
        if (dataAccess != null) {
            Date before = new Date();
            ServiceMonitoringSnapshot monitoredData = dataAccess.getStructuredMonitoredData(serviceConfiguration);
            Date after = new Date();
            log.debug("Raw monitoring data access time in ms:  " + new Date(after.getTime() - before.getTime()).getTime());
            return monitoredData;
        } else {
            log.warn("Data Access source not set yet on SystemControl");
            return new ServiceMonitoringSnapshot();
        }
    }

    public synchronized ServiceMonitoringSnapshot getAggregatedMonitoringDataOverTime(List<ServiceMonitoringSnapshot> serviceMonitoringSnapshots) {
        if (serviceMonitoringSnapshots.size() > 1) {
            return instantMonitoringDataEnrichmentEngine.aggregateMonitoringDataOverTime(compositionRulesConfiguration, serviceMonitoringSnapshots);
        } else {
            return instantMonitoringDataEnrichmentEngine.enrichMonitoringData(compositionRulesConfiguration, serviceMonitoringSnapshots.get(0));
        }
    }

    public synchronized Collection<Metric> getAvailableMetricsForMonitoredElement(MonitoredElement MonitoredElement) {
        if (dataAccess != null) {
            return dataAccess.getAvailableMetricsForMonitoredElement(MonitoredElement);
        } else {
            log.warn("Data Access source not set yet on SystemControl");
            return new ArrayList<Metric>();
        }
    }

    public synchronized void addMetricFilter(MetricFilter metricFilter) {
        if (dataAccess != null) {
            dataAccess.addMetricFilter(metricFilter);
        } else {
            log.warn("Data Access source not set yet on SystemControl");
        }
    }

    public synchronized void addMetricFilters(Collection<MetricFilter> newFilters) {
        if (dataAccess != null) {
            dataAccess.addMetricFilters(newFilters);
        } else {
            log.warn("Data Access source not set yet on SystemControl");
        }
    }

    public synchronized void removeMetricFilter(MetricFilter metricFilter) {
        if (dataAccess != null) {
            dataAccess.removeMetricFilter(metricFilter);
        } else {
            log.warn("Data Access source not set yet on SystemControl");
        }
    }

    public synchronized void removeMetricFilters(Collection<MetricFilter> filtersToRemove) {
        if (dataAccess != null) {
            dataAccess.removeMetricFilters(filtersToRemove);
        } else {
            log.warn("Data Access source not set yet on SystemControl");
        }
    }

    public synchronized void setMonitoringIntervalInSeconds(int monitoringIntervalInSeconds) {
        this.monitoringIntervalInSeconds = monitoringIntervalInSeconds;
    }

    public synchronized void setNrOfMonitoringWindowsToAggregate(int aggregationIntervalInSeconds) {
        this.aggregationWindowsCount = aggregationIntervalInSeconds;
    }

    public synchronized void startMonitoring() {
        log.debug("Starting monitoring for serviceConfiguration {}", serviceConfiguration.getId());
        persistenceSQLAccess.writeConfiguration(new ConfigurationXMLRepresentation(serviceConfiguration, compositionRulesConfiguration, requirements));

        // list all MELA datasources from application context
        Map<String, AbstractDataSource> dataSources = context.getBeansOfType(AbstractDataSource.class);

        for (String dataSourceName : dataSources.keySet()) {
            AbstractDataSource dataSource = dataSources.get(dataSourceName);
            log.debug("Found Datasource '{}': {}", dataSourceName, dataSource);
            dataAccess.addDataSource(dataSource);

        }

        // set metric filters on data access
        for (CompositionRule compositionRule : compositionRulesConfiguration.getMetricCompositionRules().getCompositionRules()) {
            // go trough each CompositionOperation and extract the source
            // metrics

            List<CompositionOperation> queue = new ArrayList<CompositionOperation>();
            queue.add(compositionRule.getOperation());

            while (!queue.isEmpty()) {
                CompositionOperation operation = queue.remove(0);
                queue.addAll(operation.getSubOperations());

                Metric targetMetric = operation.getTargetMetric();
                // metric can be null if a composition rule artificially creates
                // a metric using SET_VALUE
                if (targetMetric != null) {
                    MetricFilter metricFilter = new MetricFilter();
                    metricFilter.setId(targetMetric.getName() + "_Filter");
                    metricFilter.setLevel(operation.getMetricSourceMonitoredElementLevel());
                    Collection<Metric> metrics = new ArrayList<Metric>();
                    metrics.add(new Metric(targetMetric.getName()));
                    metricFilter.setMetrics(metrics);
                    dataAccess.addMetricFilter(metricFilter);
                }
            }
        }

        monitoringTimer = new Timer();

        task = new TimerTask() {
            @Override
            public void run() {
                if (serviceConfiguration != null) {
                    log.debug( "Refreshing data");
                    ServiceMonitoringSnapshot monitoringData = getRawMonitoringData();

                    if (monitoringData != null) {
                        historicalMonitoringData.add(monitoringData);
                        // remove the oldest and add the new value always
                        if (historicalMonitoringData.size() > aggregationWindowsCount) {
                            historicalMonitoringData.remove(0);
                        }

                        if (compositionRulesConfiguration != null) {
                            ServiceMonitoringSnapshot latestMonitoringData = getAggregatedMonitoringDataOverTime(historicalMonitoringData);
                            for (MonitoredElement element : actionsInExecution.keySet()) {
                                latestMonitoringData.setExecutingActions(element, actionsInExecution.get(element));
                            }

                            // write monitoring data in sql
                            Date before = new Date();
                            String timestamp = "" + new Date().getTime();

                            // add new timestamp
                            persistenceSQLAccess.writeInTimestamp(timestamp, serviceConfiguration.getId());

                            // write structured monitoring data
                            persistenceSQLAccess.writeMonitoringData(timestamp, latestMonitoringData);

                            // write monitoring data directly collected
                            persistenceSQLAccess.writeRawMonitoringData(timestamp, dataAccess.getFreshestMonitoredData());

                            // update and store elasticity pathway
                            // LightweightEncounterRateElasticityPathway
                            // elasticityPathway =
                            // persistenceSQLAccess.extractLatestElasticityPathway();

                            // in future just update pathway. now recompute
                            // elasticityPathway.trainElasticityPathway(null)

                            Date after = new Date();
                            log.debug("DaaS data writing time in ms:  " + new Date(after.getTime() - before.getTime()).getTime());
                            // elasticitySpaceFunction.trainElasticitySpace(latestMonitoringData);
                        }
                    } else {
                        // stop the monitoring if the data replay is done
                        // this.cancel();
                        log.error("Monitoring data is NULL");
                    }
                } else {
                    log.warn("No service configuration");
                }
            }
        };
        log.debug( "Scheduling data pool at " + monitoringIntervalInSeconds + " seconds");
        // repeat the monitoring every monitoringIntervalInSeconds seconds
        monitoringTimer.schedule(task, 0, monitoringIntervalInSeconds * 1000);

    }

    public synchronized void stopMonitoring() {
        /*try {
            persistenceSQLAccess.closeConnection();
        } catch (SQLException ex) {
            Logger.getLogger(DataCollectionService.class.getName()).log(Level.ERROR, null, ex);
        }*/
        // task.cancel();
        monitoringTimer.cancel();
    }
    // public synchronized String getLatestMonitoringDataINJSON() {
    // Date before = new Date();
    // String converted =
    // ConvertToJSON.convertMonitoringSnapshot(latestMonitoringData,
    // requirements, actionsInExecution);
    // Date after = new Date();
    // log.warn(
    // "Get Mon Data time in ms:  " + new Date(after.getTime() -
    // before.getTime()).getTime());
    // return converted;
    // }
    //
    // public synchronized String getMetricCompositionRules() {
    // if (compositionRulesConfiguration != null) {
    // return
    // ConvertToJSON.convertToJSON(compositionRulesConfiguration.getMetricCompositionRules());
    // } else {
    // JSONObject jsonObject = new JSONObject();
    // jsonObject.put("name", "No composition rules yet");
    // return jsonObject.toJSONString();
    // }
    // }
}
