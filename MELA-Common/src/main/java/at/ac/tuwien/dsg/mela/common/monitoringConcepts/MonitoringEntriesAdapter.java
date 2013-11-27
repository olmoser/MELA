/**
 * Copyright 2013 Technische Universitat Wien (TUW), Distributed Systems Group E184
 *
 * This work was partially supported by the European Commission in terms of the CELAR FP7 project (FP7-ICT-2011-8 \#317790)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
 
package at.ac.tuwien.dsg.mela.common.monitoringConcepts;

import java.util.HashMap;
import java.util.Map;
import javax.xml.bind.annotation.adapters.XmlAdapter;

/**
 * Author: Daniel Moldovan 
 * E-Mail: d.moldovan@dsg.tuwien.ac.at 

 **/
public class MonitoringEntriesAdapter extends XmlAdapter<MonitoredEntries, Map<Metric, MetricValue>> {

    @Override
    public Map<Metric, MetricValue> unmarshal(MonitoredEntries in) throws Exception {
        HashMap<Metric, MetricValue> hashMap = new HashMap<Metric, MetricValue>();
        for (MonitoredEntry entry : in.entries()) {
            hashMap.put(entry.getMetric(), entry.getValue());
        }
        return hashMap;
    }

    @Override
    public MonitoredEntries marshal(Map<Metric, MetricValue> map) throws Exception {
        MonitoredEntries props = new MonitoredEntries();
        for (Map.Entry<Metric, MetricValue> entry : map.entrySet()) {
            props.addEntry(new MonitoredEntry(entry.getKey(), entry.getValue()));
        }
        return props;
    }
}
