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
package main;

import at.ac.tuwien.dsg.mela.dataservice.DataCollectionService;
import at.ac.tuwien.dsg.mela.dataservice.MELADataService;
import at.ac.tuwien.dsg.mela.dataservice.api.DataServiceActiveMQAPI;

/**
 * Author: Daniel Moldovan E-Mail: d.moldovan@dsg.tuwien.ac.at  *
 *
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        final MELADataService service = new MELADataService();
        service.startServer();
        
        DataCollectionService dataCollectionService =  DataCollectionService.getInstance();
        DataServiceActiveMQAPI activeMQAPI = new DataServiceActiveMQAPI(dataCollectionService);
                
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                service.stopServer();
            }
        });
        
        activeMQAPI.run();
    }
}