/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
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
package org.onebusaway.nyc.vehicle_tracking.impl.queue;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.nyc.queue.QueueListenerTask;
import org.onebusaway.nyc.queue.model.RealtimeEnvelope;
import org.onebusaway.nyc.vehicle_tracking.services.queue.InputService;
import org.onebusaway.nyc.vehicle_tracking.services.queue.InputTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

public abstract class InputQueueListenerTask extends QueueListenerTask implements InputTask{
  
  InputService _inputService;

  @SuppressWarnings("deprecation")
  public InputQueueListenerTask() {
    /*
     * Use JAXB annotation interceptor so we pick up autogenerated annotations
     * from XSDs
     */
	  final AnnotationIntrospector introspector = new JaxbAnnotationIntrospector(_mapper.getTypeFactory());
	  _mapper.setAnnotationIntrospector(introspector);
  }
  
  @Autowired
  @Qualifier("queueInputService")
  public void setInputService(InputService inputService){
	  _inputService = inputService;
  }
  
  public RealtimeEnvelope deserializeMessage(String contents){
    return _inputService.deserializeMessage(contents);
  }

  @Override
  @Refreshable(dependsOn = {
      "inference-engine.inputQueueHost", "inference-engine.inputQueuePort",
      "inference-engine.inputQueueName"})
  public void startListenerThread() {
    if (_initialized == true) {
      _log.warn("Configuration service tried to reconfigure inference input queue service; this service is not reconfigurable once started.");
      return;
    }

    final String host = getQueueHost();
    final String queueName = getQueueName();
    final Integer port = getQueuePort();

    if (host == null || queueName == null || port == null) {
      _log.info("Inference input queue is not attached; input hostname was not available via configuration service.");
      return;
    }

    _log.info("realtime archive listening on " + host + ":" + port + ", queue="
        + queueName);
    try {
      initializeQueue(host, queueName, port);
    } catch (final InterruptedException ie) {
      return;
    }
  }

  public String getDepotPartitionKey() {
    return null;
  }

  @Override
  public String getQueueHost() {
    return _configurationService.getConfigurationValueAsString(
        "inference-engine.inputQueueHost", null);
  }

  @Override
  public String getQueueName() {
    return _configurationService.getConfigurationValueAsString(
        "inference-engine.inputQueueName", null);
  }

  @Override
  public Integer getQueuePort() {
    return _configurationService.getConfigurationValueAsInteger(
        "inference-engine.inputQueuePort", 5563);
  }

  @Override
  @PostConstruct
  public void setup() {
	
    super.setup();
  }

  @Override
  @PreDestroy
  public void destroy() {
    super.destroy();
  }

}
