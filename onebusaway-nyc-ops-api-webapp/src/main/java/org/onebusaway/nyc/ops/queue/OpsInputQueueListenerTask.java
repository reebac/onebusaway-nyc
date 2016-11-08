package org.onebusaway.nyc.ops.queue;

import java.util.Date;
import java.util.TimeZone;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.onebusaway.container.refresh.Refreshable;
import org.onebusaway.nyc.queue.QueueListenerTask;
import org.onebusaway.nyc.queue.model.RealtimeEnvelope;
import org.onebusaway.nyc.report.impl.CcLocationCache;
import org.onebusaway.nyc.report.model.CcLocationReportRecord;
import org.onebusaway.nyc.report.services.RecordValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

public class OpsInputQueueListenerTask extends QueueListenerTask {

  public static final int DELAY_THRESHOLD = 10 * 1000;

  protected static Logger _log = LoggerFactory.getLogger(OpsInputQueueListenerTask.class);

  private RecordValidationService validationService;

  private CcLocationCache _ccLocationCache;

  @Autowired
  public void setCcLocationCache(CcLocationCache cache) {
    _ccLocationCache = cache;
  }

  @Autowired
  public void setValidationService(RecordValidationService validationService) {
    this.validationService = validationService;
  }

  // offset of timezone (-04:00 or -05:00)
  private String _zoneOffset = null;
  private String _systemTimeZone = null;
  private long zoneOffsetWindow = System.currentTimeMillis();

  public OpsInputQueueListenerTask() {
    /*
     * use Jaxb annotation interceptor so we pick up autogenerated annotations
     * from XSDs
     */
	  _mapper.setAnnotationIntrospector(new JaxbAnnotationIntrospector(
				_mapper.getTypeFactory()));
  }

  public RealtimeEnvelope deserializeMessage(String contents) {
    RealtimeEnvelope message = null;
    try {
      JsonNode wrappedMessage = _mapper.readValue(contents, JsonNode.class);
      String ccLocationReportString = wrappedMessage.get("RealtimeEnvelope").toString();

      message = _mapper.readValue(ccLocationReportString,
          RealtimeEnvelope.class);
    } catch (Exception e) {
      _log.warn("Received corrupted message from queue; discarding: "
          + e.getMessage());
      _log.warn("Contents: " + contents);
    }
    return message;
  }

  @Refreshable(dependsOn = {
      "inference-engine.inputQueueHost", "inference-engine.inputQueuePort",
      "inference-engine.inputQueueName"})
  public void startListenerThread() {
    if (this._initialized) {
      _log.warn("Configuration service reconfiguring inference input queue service");
    }

    String host = getQueueHost();
    String queueName = getQueueName();
    Integer port = getQueuePort();

    if (host == null || queueName == null || port == null) {
      _log.error("Inference input queue is not attached; input hostname was not available via configuration service.");
      return;
    }

    _log.warn("realtime archive listening on " + host + ":" + port + ", queue="
        + queueName);
    try {
      initializeQueue(host, queueName, port);
      _log.warn("queue config:" + queueName + " COMPLETE");
    } catch (InterruptedException ie) {
      _log.error("queue " + queueName + " interrupted");
      return;
    } catch (Throwable t) {
      _log.error("queue " + queueName + " init failed:", t);
    }
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

  public String getQueueDisplayName() {
    return "archive_realtime";
  }

  @Override
  public Integer getQueuePort() {
    return _configurationService.getConfigurationValueAsInteger(
        "inference-engine.inputQueuePort", 5563);
  }

  @Override
  // this method can't throw exceptions or it will stop the queue
  // listening
  public boolean processMessage(String address, byte[] buff) {
    String contents = new String(buff);
    RealtimeEnvelope envelope = null;
    CcLocationReportRecord record = null;
    try {

      envelope = deserializeMessage(contents);

      if (envelope == null || envelope.getCcLocationReport() == null) {
        _log.error("Message discarded, probably corrupted, contents= "
            + contents);
        return false;
      }

      boolean validEnvelope = validationService.validateRealTimeRecord(envelope);
      if (validEnvelope) {
        record = new CcLocationReportRecord(envelope, contents, getZoneOffset());
        // update cache for operational API
        _ccLocationCache.put(record);
        
      } else {
        long vehicleId = envelope.getCcLocationReport().getVehicle().getVehicleId();
        _log.error(
            "Discarding real time record for vehicle : {} as it does not meet the "
                + "required database constraints", vehicleId);
      }
      
      if (System.currentTimeMillis() - zoneOffsetWindow > 60 * 60 * 1000) {
        // reset zoneoffset once an hour
        _zoneOffset = null;
        zoneOffsetWindow = System.currentTimeMillis();
      }
    } catch (Throwable t) {
      _log.error("Exception processing contents= " + contents, t);
    }

    return true;
  }

  @PostConstruct
  public void setup() {
    super.setup();
    // make parsing lenient
    _mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        false);
    // set a reasonable default
    _systemTimeZone = _configurationService.getConfigurationValueAsString(
        "archive.systemTimeZone", "America/New_York");
  }

  @PreDestroy
  public void destroy() {
    super.destroy();
  }

  /**
   * Return the offset in an tz-offset string fragment ("-04:00" or "-5:00")
   * based on the daylight savings rules in effect during the given date. This
   * method assumes timezone is a standard hour boundary away from GMT.
   * 
   * Package private for unit tests.
   * 
   * @param date when to consider the zoneoffset. Now makes the most sense, but
   *          can be historical/future for unit testing
   * @param systemTimeZone the java string representing a timezone, such as
   *          "America/New_York"
   */
  String getZoneOffset(Date date, String systemTimeZone) {
    if (date == null)
      return null;
    // cache _zoneOffset
    if (_zoneOffset == null) {
      long millisecondOffset;
      // use systemTimeZone if available
      if (systemTimeZone != null) {
        millisecondOffset = TimeZone.getTimeZone(systemTimeZone).getOffset(
            date.getTime());
      } else {
        // use JVM default otherwise
        millisecondOffset = TimeZone.getDefault().getOffset(date.getTime());
      }
      String plusOrMinus = (millisecondOffset <= 0 ? "-" : "+");
      if (millisecondOffset == 0) {
        _zoneOffset = plusOrMinus + "00:00";
      } else {
        // format 1st arg 0-padded to a width of 2
        _zoneOffset = plusOrMinus
            + String.format("%1$02d",
                Math.abs(millisecondOffset / (1000 * 60 * 60))) + ":00";
      }
    }
    return _zoneOffset;

  }

  private String getZoneOffset() {
    return getZoneOffset(new Date(), _systemTimeZone);
  }


}
