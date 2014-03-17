package org.onebusaway.nyc.transit_data_manager.bundle;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.onebusaway.nyc.transit_data_manager.bundle.model.Bundle;
import org.onebusaway.nyc.transit_data_manager.bundle.model.BundleMetadata;

public class BundleProvider {

  private static final String META_DATA_LOCATION = "metadata.json";
  private static final String ENV = "prod";
  private BundleSource bundleSource;
  
  public BundleProvider(BundleSource bundleSource) {
    super();
    
    this.bundleSource = bundleSource;
  }
  
  public List<Bundle> getBundles () {
    return bundleSource.getBundles();
  }
  
  public File getBundleFile (String bundleId, String relativeFilePath) throws FileNotFoundException {
    return bundleSource.getBundleFile(bundleId, relativeFilePath);
  }
  
  public File getStagedBundleFile(String bundleDirectory, String relativeFilePath) throws FileNotFoundException {
    
    return bundleSource.getBundleFile(bundleDirectory, ENV, relativeFilePath);
  }
  public boolean checkIsValidBundleFile (String bundleId, String relativeFilePath) {
    return bundleSource.checkIsValidBundleFile(bundleId, relativeFilePath);
  }

  public BundleMetadata getMetadata(String stagingDirectory) throws Exception {
    File file = bundleSource.getBundleFile(stagingDirectory, ENV, META_DATA_LOCATION);
    ObjectMapper mapper = new ObjectMapper();
    BundleMetadata meta = mapper.readValue(file, BundleMetadata.class);
    return meta;
  }

  public void stage(String stagingDirectory, String bundleDir, String bundleName) throws Exception {
    bundleSource.stage(stagingDirectory, ENV, bundleDir, bundleName);
    
  }
}
