package org.onebusaway.nyc.gtfsrt.integration_tests;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.onebusaway.utility.DateLibrary;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Controller to absract actions against gtfsrt-webapp from the
 * integration tests.
 */
public class WebController {

    public void setBundle(String bundleId, Date date) throws Exception {
        get("/change-bundle.do?bundleId="
                + bundleId + "&time=" + DateLibrary.getTimeAsIso8601String(date));
    }

    public void setVehicleLocationRecords(InputStream data) throws Exception {
        post("/input/vehicleLocationRecords", data);
    }

    public void setTimePredictionRecordsTime(Date serviceDate) throws Exception {
        Map<String, String> params = new HashMap<String, String>();
        params.put("time", String.valueOf(serviceDate.getTime()));
        post("/input/timePredictionRecordsTime", params);
    }

    public void setTimePredictionRecords(InputStream data) throws Exception {
        post("/input/timePredictionRecords", data);
    }

    private void get(String apiPathAndEncodedParams) throws Exception {
        String port = getPort();
        String context = getContext();
        String url = "http://localhost:" + port + context
                + apiPathAndEncodedParams;

        System.out.println("url=" + url);
        HttpClient client = new HttpClient();
        GetMethod get = new GetMethod(url);
        client.executeMethod(get);

        validateSuccess(get);
    }

    private void post(String apiPath, Map<String, String> params) throws Exception {
        String port = getPort();
        String context = getContext();
        String url = "http://localhost:" + port + context
                + apiPath;

        System.out.println("url=" + url);
        HttpClient client = new HttpClient();
        PostMethod postMethod = new PostMethod(url);
        for (String key : params.keySet()) {
            postMethod.addParameter(key, params.get(key));
        }
        client.executeMethod(postMethod);
        validateSuccess(postMethod);
    }

    private void post(String apiPath, InputStream data) throws Exception {
        String port = getPort();
        String context = getContext();
        String url = "http://localhost:" + port + context
                + apiPath;

        System.out.println("url=" + url);
        HttpClient client = new HttpClient();
        PostMethod postMethod = new PostMethod(url);
        postMethod.setRequestEntity(new InputStreamRequestEntity(data));
        client.executeMethod(postMethod);
        validateSuccess(postMethod);
    }

    private void validateSuccess(HttpMethod method) throws Exception {
        String response = method.getResponseBodyAsString();
        if (!response.equals("OK") && 200 != method.getStatusCode())
            throw new Exception("Request failed! "
                    + method.getURI()
                    + ": (" + method.getStatusCode()
                    + ":" + method.getStatusText() + ")");

    }

    private String getPort() {
        return System.getProperty(
                "org.onebusaway.webapp.port", "9000");
    }
    private String getContext() {
        return System.getProperty(
                "org.onebusaway.webapp.context", "/onebusaway-nyc-gtfsrt-webapp");
    }

}