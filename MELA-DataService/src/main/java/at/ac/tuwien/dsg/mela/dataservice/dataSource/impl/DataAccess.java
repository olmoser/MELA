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
package at.ac.tuwien.dsg.mela.dataservice.dataSource.impl;

import at.ac.tuwien.dsg.mela.common.jaxbEntities.monitoringConcepts.MetricInfo;
import at.ac.tuwien.dsg.mela.common.jaxbEntities.monitoringConcepts.MonitoredElementData;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.*;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.MonitoredElement.MonitoredElementLevel;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.dataCollection.AbstractDataAccess;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.dataCollection.AbstractDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Author: Daniel Moldovan E-Mail: d.moldovan@dsg.tuwien.ac.at *
 */
@Service("defaultDataAccess")
public class DataAccess extends AbstractDataAccess {

    static final Logger log = LoggerFactory.getLogger(DataAccess.class);

    public DataAccess() {
    }

    /**
     * @param m the root element of the Service Structure
     *          hierarchy
     * @return ServiceMonitoringSnapshot containing the monitored data organized
     * both in tree and by level Searches in the Ganglia HOSTS monitoring for
     * MonitoredElement ID, and if it finds such ID searches it in the supplied
     * Service structure, after, adds the monitoring information as a
     * sub-element MonitoredElement of VM level to the element having the found
     * ID
     */
    @Override
    public synchronized ServiceMonitoringSnapshot getStructuredMonitoredData(MonitoredElement m) {

        if (m == null) {
            log.warn("No supplied service configuration");
            return new ServiceMonitoringSnapshot();
        }

        MonitoredElement structureRoot = m.clone();

        // extract all VMs from the service structure
        // Map<MonitoredElement, MonitoredElement> vms = new
        // LinkedHashMap<MonitoredElement, MonitoredElement>();

        /**
         * Linear representation of MonitoredElement hierarchical tree. also
         * maintains the three structure using the .children relationship
         * between MonitoredElementMonitoringSnapshot instances
         */
        Map<MonitoredElement, MonitoredElement> elements = new LinkedHashMap<MonitoredElement, MonitoredElement>();

        ServiceMonitoringSnapshot serviceMonitoringSnapshot = new ServiceMonitoringSnapshot();

        // traverse the MonitoredElement hierarchical tree in BFS and extract
        // the serviceStructure elements
        List<MonitoredElementMonitoringSnapshot> bfsTraversalQueue = new ArrayList<MonitoredElementMonitoringSnapshot>();
        MonitoredElementMonitoringSnapshot rootMonitoringSnapshot = new MonitoredElementMonitoringSnapshot(structureRoot, new LinkedHashMap<Metric, MetricValue>());

        bfsTraversalQueue.add(rootMonitoringSnapshot);
        serviceMonitoringSnapshot.addMonitoredData(rootMonitoringSnapshot);

        // used in determining if we have specified a VM, or just ServiceUnit
        MonitoredElement lowestLevelFoundMonitoredElement = null;
        MonitoredElementMonitoringSnapshot lowestLevelFoundMonitoredSnapshot = null;

        while (!bfsTraversalQueue.isEmpty()) {
            MonitoredElementMonitoringSnapshot element = bfsTraversalQueue.remove(0);
            MonitoredElement processedElement = element.getMonitoredElement();
            elements.put(processedElement, processedElement);
            lowestLevelFoundMonitoredElement = processedElement;
            lowestLevelFoundMonitoredSnapshot = element;

//            if(processedElement.getLevel().equals(MonitoredElement.MonitoredElementLevel.VM)){
//                vms.put(processedElement, processedElement);
//            }

            for (MonitoredElement child : processedElement.getContainedElements()) // add empty monitoring data
            // for each serviceStructure element, to serve as a place where
            // in the future composite metrics can be added
            {
                MonitoredElementMonitoringSnapshot monitoredElementMonitoringSnapshot = new MonitoredElementMonitoringSnapshot(child, new LinkedHashMap<Metric, MetricValue>());
                element.addChild(monitoredElementMonitoringSnapshot);
                serviceMonitoringSnapshot.addMonitoredData(monitoredElementMonitoringSnapshot);
                bfsTraversalQueue.add(monitoredElementMonitoringSnapshot);

            }

        }

        // go through each monitored element and update the service monitoring
        // snapshot
        for (AbstractDataSource dataSource : freshestMonitoredData.keySet()) {
            // maybe in the future we use data source information, but now we
            // extract the monitored data directly

            // maybe in the future we use information from MonitoringData, but
            // now we extract the monitored data elements directly
            for (MonitoredElementData elementData : freshestMonitoredData.get(dataSource).getMonitoredElementDatas()) {

                // create MonitoredElementMonitoringSnapshot for each element
                HashMap<Metric, MetricValue> monitoredMetricValues = new LinkedHashMap<Metric, MetricValue>();
                for (MetricInfo metricInfo : elementData.getMetrics()) {
                    Metric metric = new Metric();
                    metric.setName(metricInfo.getName());
                    metric.setMeasurementUnit(metricInfo.getUnits());
                    MetricValue metricValue = new MetricValue(metricInfo.getConvertedValue());
                    monitoredMetricValues.put(metric, metricValue);
                }
                MonitoredElement monitoredElement = elementData.getMonitoredElement();

                if (elements.containsKey(monitoredElement)) {
                    // get the monitored element from the supplied service
                    // structure, where is connected with service units
                    MonitoredElement structureElement = elements.get(monitoredElement);
                    MonitoredElementMonitoringSnapshot monitoredElementMonitoringSnapshot = new MonitoredElementMonitoringSnapshot(structureElement, monitoredMetricValues);

                    // if data exists, it updates it, otherwise creates entry
                    serviceMonitoringSnapshot.addMonitoredData(monitoredElementMonitoringSnapshot);
                } else {
                    // else we add all VMs to the found service unit
                    if (monitoredElement.getLevel().equals(MonitoredElementLevel.VM)) {

                        MonitoredElementMonitoringSnapshot monitoredElementMonitoringSnapshot = new MonitoredElementMonitoringSnapshot(monitoredElement, monitoredMetricValues);
                        // add to monitoring data tree structure

                        lowestLevelFoundMonitoredSnapshot.addChild(monitoredElementMonitoringSnapshot);

                        serviceMonitoringSnapshot.addMonitoredData(monitoredElementMonitoringSnapshot);
                        // add to structure
                        lowestLevelFoundMonitoredElement.addElement(monitoredElement);
                    }
                }
            }
        }

        // filter the monitoredMetricValues according to the metric filters if
        // such exist
        serviceMonitoringSnapshot.applyMetricFilters(metricFilters);

        return serviceMonitoringSnapshot;

    }

    @Override
    public synchronized MonitoredElementMonitoringSnapshot getSingleElementMonitoredData(MonitoredElement suppliedMonitoringElement) {
        throw new UnsupportedOperationException("getSingleElementMonitoredData not implemented");
    }

}
