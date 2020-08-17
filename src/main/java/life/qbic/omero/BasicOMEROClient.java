package life.qbic.omero;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import omero.ServerError;
import omero.api.IAdminPrx;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.DataManagerFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.model.AnnotationData;
import omero.gateway.model.ChannelData;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.ImageData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PixelsData;
import omero.gateway.model.ProjectData;
import omero.log.SimpleLogger;
import omero.model.Dataset;
import omero.model.DatasetI;
import omero.model.IObject;
import omero.model.NamedValue;
import omero.model.Project;
import omero.model.ProjectDatasetLink;
import omero.model.ProjectDatasetLinkI;
import omero.model.ProjectI;
import omero.romio.PlaneDef;

/////////////////////////////////////////////////////

public class BasicOMEROClient {

  //////////////////
  /** Reference to the gateway. */
  private Gateway gateway;
  /** The security context. */
  private SecurityContext securityContext;

  private String hostname;
  private int port;

  private String username;
  private String password;

  private String sessionId;

  private HashMap<Long, String> projectMap;
  private HashMap<Long, Set<DatasetData>> datasetMap;
  private String sessionUuid;

  public BasicOMEROClient(String username, String password, String hostname, int port) {

    this.username = username;
    this.password = password;
    this.hostname = hostname;
    this.port = port;

    this.sessionId = "";

  }

  /**
   * Connects the the omero gateway. Uses the experimenter data to update properties of this omero client instance.
   *
   * @param hostname
   * @param port
   * @param username
   * @param password
   * @see Gateway
   * @see ome.system.EventContext
   */
  private void connect(String hostname, int port, String username, String password) {

    this.gateway = new Gateway(new SimpleLogger());
    LoginCredentials cred = new LoginCredentials();
    cred.getServer().setHostname(hostname);

    if (port > 0) {
      cred.getServer().setPort(port);
    }

    cred.getUser().setUsername(username);
    cred.getUser().setPassword(password);

    try {
      ExperimenterData user = gateway.connect(cred);
      this.securityContext = new SecurityContext(user.getGroupId());
      this.sessionId = gateway.getSessionId(user);
      IAdminPrx gatewayAdminService = gateway.getAdminService(securityContext);
      this.sessionUuid = gatewayAdminService.getEventContext().sessionUuid;

    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store interaction failed.", serverError);
    }
  }

  public void connect() {
      this.connect(this.hostname, this.port, this.username, this.password);
  }

  /**
   * Returns any file annotations (information about attachments) of a given image
   *
   * @param imageID the ID of the image
   * @return A list of FileAnnotationData objects
   */
  public List<FileAnnotationData> fetchFileAnnotationDataForImage(long imageID) {
    return loadAnnotationsForImage(imageID, FileAnnotationData.class);
  }

  /**
   * Returns any map annotation data (key value pairs of metadata) of a given image
   *
   * @param imageID the ID of the image
   * @return A list of MapAnnotationData objects
   */
  public List<MapAnnotationData> fetchMapAnnotationDataForImage(long imageID) {
    return loadAnnotationsForImage(imageID, MapAnnotationData.class);
  }

  /**
   *
   * @param imageID the omero identifier for the desired image
   * @param <T> the desired subclass of {@link AnnotationData}
   * @return a List containing annotation data for the given image matching the desired class
   */
  private <T extends AnnotationData> List<T> loadAnnotationsForImage(long imageID, Class<T> type) {
    ImageData image;
    List<AnnotationData> annotations;

    try {
      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      image = browse.getImage(this.securityContext, imageID);
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }


    List<Class<? extends AnnotationData>> types = new ArrayList<>();
    types.add(type);

    try {
      MetadataFacility metadata = gateway.getFacility(MetadataFacility.class);
      annotations = metadata.getAnnotations(securityContext, image, types, null);
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }
    disconnect();

return (annotations != null) ? annotations.stream().map(annotationData -> (T) annotationData).collect(Collectors.toList()) : new ArrayList<T>();
  }

  /**
   * render buffered image of image object in Omero
   *
   * @param image imageData object from Omero
   * @param zPlane selected slide of the vertical axis of a 3D image, else 0
   * @param timePoint selected time point of a time series, else 0
   * @return a {@link BufferedImage} for the given {@link ImageData}
   */
  public BufferedImage renderImage(ImageData image, int zPlane, int timePoint) {

    BufferedImage res;

    PixelsData pixels = image.getDefaultPixels();
    long pixelsId = pixels.getId();
    RenderingEnginePrx proxy;
    ByteArrayInputStream stream;
    try {
      proxy = gateway.getRenderingService(securityContext, pixelsId);
      proxy.lookupPixels(pixelsId);
      if (!(proxy.lookupRenderingDef(pixelsId))) {
        proxy.resetDefaultSettings(true);
        proxy.lookupRenderingDef(pixelsId);
      }
      proxy.load();
      // Now can interact with the rendering engine.
      proxy.setActive(0, Boolean.FALSE);
      PlaneDef pDef = new PlaneDef();
      pDef.z = zPlane;
      pDef.t = timePoint;
      pDef.slice = omero.romio.XY.value;
      // render the data uncompressed.
      int[] uncompressed = proxy.renderAsPackedInt(pDef);
      byte[] compressed = proxy.renderCompressed(pDef);
      // Create a buffered image
      stream = new ByteArrayInputStream(compressed);

      res = ImageIO.read(stream);
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store interaction failed.", serverError);
    } catch (IOException ioException) {
      throw new RuntimeException("Image data could now be read.", ioException);
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    }

    try {
      proxy.close();
      stream.close();
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store interaction failed.", serverError);
    } catch (IOException ioException) {
      throw new RuntimeException("Stream could not be closed.", ioException);
    }
    return res;
  }

  private Gateway getGateway() {
    return gateway;
  }

  private SecurityContext getContext() {
    return securityContext;
  }

  public String getSessionId() {
    return this.sessionId;
  }

  public Map<Long, String> getProjectMap() {
    return projectMap;
  }

  public Map<Long, Set<DatasetData>> getDatasetMap() {
    return datasetMap;
  }

  public void disconnect() {
    this.gateway.disconnect();
  }

  /**
   * Tries to build an image download link for a given imageID. An exception will be thrown if the
   * image can not be downloaded due to its format
   *
   * @param imageID the omero identifier of the desired image
   * @return URL String to download the image or null
   */
  public String getImageDownloadLink(long imageID) {
    String downloadLinkAddress;
    try {
      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      ImageData image = browse.getImage(this.securityContext, imageID);
      disconnect();
      if (image.getFormat() != null) {
        downloadLinkAddress = "http://" + hostname + "/omero/webgateway/archived_files/download/" + imageID + "?server=1&bsession=" + sessionUuid;
      } else {
        throw new IllegalArgumentException("No image format given. Image is not available for download.");
      }
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }
    return downloadLinkAddress;
  }

  /**
   * Tries to build an image download link for a given annotation ID. No checks are performed if
   * that ID belongs to a file.
   *
   * @param annotationID
   * @return URL String to download the file
   */
  public String getAnnotationFileDownloadLink(long annotationID) {
    return hostname + "/omero/webclient/annotation/" + annotationID;
  }

  public HashMap<Long, String> loadProjects() {

    this.projectMap = new HashMap<Long, String>();
    this.datasetMap = new HashMap<Long, Set<DatasetData>>();

    try {

      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      Collection<ProjectData> projects = browse.getProjects(securityContext);

      Iterator<ProjectData> i = projects.iterator();
      ProjectData project;
      while (i.hasNext()) {
        project = i.next();

        String name = project.getName();
        long id = project.getId();

        this.projectMap.put(id, name);
        this.datasetMap.put(id, project.getDatasets());
      }

    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    return this.projectMap;
  }

  public HashMap<String, String> getProjectInfo(long projectId) {

    HashMap<String, String> projectInfo = new HashMap<String, String>();

    try {

      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      Collection<ProjectData> projects = browse.getProjects(securityContext);

      Iterator<ProjectData> i = projects.iterator();
      ProjectData project;
      while (i.hasNext()) {
        project = i.next();

        if (project.getId() == projectId) {

          projectInfo.put("name", project.getName());
          projectInfo.put("desc", project.getDescription());

          break;
        }
      }
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    return projectInfo;
  }

  public HashMap<Long, HashMap<String, String>> getDatasets(long projectId) {

    HashMap<Long, HashMap<String, String>> datasetList =
        new HashMap<Long, HashMap<String, String>>();

    Set<DatasetData> datasets = this.datasetMap.get(projectId);

    Iterator<DatasetData> iterator = datasets.iterator();
    DatasetData dataset;
    while (iterator.hasNext()) {
      dataset = iterator.next();

      HashMap<String, String> datasetInfo = new HashMap<String, String>();
      datasetInfo.put("name", dataset.getName());
      datasetInfo.put("desc", dataset.getDescription());

      datasetList.put(dataset.getId(), datasetInfo);

    }

    return datasetList;

  }

  public long createProject(String name, String desc) {

    DataManagerFacility dm;
    try {
      dm = gateway.getFacility(DataManagerFacility.class);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    }

    Project proj = new ProjectI();
      proj.setName(omero.rtypes.rstring(name));
      proj.setDescription(omero.rtypes.rstring(desc));

    IObject r;
    try {
      r = dm.saveAndReturnObject(this.securityContext, proj);
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    return r.getId().getValue();

  }

  public long createDataset(long projectId, String name, String desc) {

    DataManagerFacility dm = null;
    try {
      dm = gateway.getFacility(DataManagerFacility.class);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    }

    Dataset dataset = new DatasetI();
    dataset.setName(omero.rtypes.rstring(name));
    dataset.setDescription(omero.rtypes.rstring(desc));
    ProjectDatasetLink link = new ProjectDatasetLinkI();
    link.setChild(dataset);
    link.setParent(new ProjectI(projectId, false));

    IObject r = null;
    try {
      r = dm.saveAndReturnObject(this.securityContext, link);
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    ProjectDatasetLink remote_link = (ProjectDatasetLink) r;
    return remote_link.getChild().getId().getValue();


  }

  public void addMapAnnotationToProject(long projectId, String key, String value) {

    List<NamedValue> result = new ArrayList<NamedValue>();
    result.add(new NamedValue(key, value));

    MapAnnotationData data = new MapAnnotationData();
    data.setContent(result);

    // Use the following namespace if you want the annotation to be editable
    // in the webclient and insight
    data.setNameSpace(MapAnnotationData.NS_CLIENT_CREATED);

    try {
      DataManagerFacility fac = gateway.getFacility(DataManagerFacility.class);
      fac.attachAnnotation(securityContext, data, new ProjectData(new ProjectI(projectId, false)));
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

  }

  public void addMapAnnotationToDataset(long datasetId, String key, String value) {

    List<NamedValue> result = new ArrayList<NamedValue>();
    result.add(new NamedValue(key, value));

    MapAnnotationData data = new MapAnnotationData();
    data.setContent(result);

    data.setNameSpace(MapAnnotationData.NS_CLIENT_CREATED);
    try {
      DataManagerFacility fac = gateway.getFacility(DataManagerFacility.class);
      fac.attachAnnotation(securityContext, data, new DatasetData(new DatasetI(datasetId, false)));
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }
  }

  public HashMap<Long, String> getImages(long datasetId) {

    HashMap<Long, String> imageList = new HashMap<Long, String>();

    try {
      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images = browse.getImagesForDatasets(securityContext, Arrays.asList(datasetId));

      Iterator<ImageData> j = images.iterator();
      ImageData image;
      while (j.hasNext()) {
        image = j.next();
        imageList.put(image.getId(), image.getName());
      }
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    return imageList;
  }

  public HashMap<String, String> getImageInfo(long datasetId, long imageId) {

    HashMap<String, String> imageInfo = new HashMap<String, String>();

    try {

      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images =
          browse.getImagesForDatasets(this.securityContext, Arrays.asList(datasetId));

      Iterator<ImageData> j = images.iterator();
      ImageData image = null;
      while (j.hasNext()) {
        image = j.next();
        if (image.getId() == imageId) {
          break;
        }
      }

      if (image != null) {

        imageInfo.put("name", image.getName());
        imageInfo.put("desc", image.getDescription());

        PixelsData pixels = image.getDefaultPixels();
        int sizeZ = pixels.getSizeZ(); // The number of z-sections.
        int sizeT = pixels.getSizeT(); // The number of timepoints.
        int sizeC = pixels.getSizeC(); // The number of channels.
        int sizeX = pixels.getSizeX(); // The number of pixels along the X-axis.
        int sizeY = pixels.getSizeY(); // The number of pixels along the Y-axis.

        imageInfo.put("size",
            String.valueOf(sizeX) + " x " + String.valueOf(sizeY) + " x " + String.valueOf(sizeZ));
        imageInfo.put("tps", String.valueOf(sizeT));

        MetadataFacility mdf = gateway.getFacility(MetadataFacility.class);

        String channelNamesString = "";
        List<ChannelData> data = mdf.getChannelData(securityContext, imageId);
        for (ChannelData c : data) {
          channelNamesString = channelNamesString + c.getName() + ", ";
        }
        channelNamesString = channelNamesString.substring(0, channelNamesString.length() - 2);

        imageInfo.put("channels", channelNamesString);
      }



    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    }

    return imageInfo;
  }

  /**
   * This method returns a http address at which the given image can be viewed using the omero web client.
   * @param imageId the omero id of a selected image
   * @return an address at which the given image can be viewed using the omero web client
   */
  public String composeImageDetailAddress(long imageId) {
    int serverId = 1;
    return "http://"
            + hostname
            + "/omero/webclient/img_detail/"
            + imageId
            + "?server="
            + serverId
            + "&bsession="
            + sessionUuid;
  }

  /**
   *
   *
   * @param datasetId
   * @param imageId
   * @return
   */
  public ByteArrayInputStream getThumbnail(long datasetId, long imageId) {

    ThumbnailStorePrx store = null;
    ByteArrayInputStream imageByteStream = null;
    try {
      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images =
          browse.getImagesForDatasets(this.securityContext, Collections.singletonList(datasetId));

      Iterator<ImageData> j = images.iterator();
      ImageData image = null;
      while (j.hasNext()) {
        image = j.next();
        if (image.getId() == imageId) {
          break;
        }
      }

      store = this.gateway.getThumbnailService(securityContext);

      PixelsData pixels = Objects.requireNonNull(image).getDefaultPixels();
      store.setPixelsId(pixels.getId());
      byte[] array = store.getThumbnail(omero.rtypes.rint(96), omero.rtypes.rint(96));
      imageByteStream = new ByteArrayInputStream(array);

    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ExecutionException executionException) {
      throw new RuntimeException("Task aborted unexpectedly.", executionException);
    } catch (DSAccessException dsAccessException) {
      throw new RuntimeException("Could not pull data from the omero server.", dsAccessException);
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store interaction failed.", serverError);
    }

    try {
      store.close();
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store could not be closed.", serverError);
    }

    return imageByteStream;

  }

}
