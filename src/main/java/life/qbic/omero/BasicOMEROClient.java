package life.qbic.omero;

import com.beust.jcommander.converters.FileConverter;
import edu.umd.cs.findbugs.ba.AnnotationEnumeration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

import loci.poi.util.TempFile;
import ome.xml.model.FileAnnotation;
import omero.ServerError;
import omero.api.ITypesPrx;
import omero.api.RawFileStore;
import omero.api.RawFileStorePrx;
import omero.api.RenderingEnginePrx;
import omero.api.SearchPrx;
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
import omero.gateway.model.FileData;
import omero.gateway.model.ImageData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PixelsData;
import omero.gateway.model.ProjectData;
import omero.log.SimpleLogger;
import omero.model.Annotation;
import omero.model.Dataset;
import omero.model.DatasetI;
import omero.model.IObject;
import omero.model.Image;
import omero.model.NamedValue;
import omero.model.OriginalFile;
import omero.model.Project;
import omero.model.ProjectDatasetLink;
import omero.model.ProjectDatasetLinkI;
import omero.model.ProjectI;
import omero.romio.PlaneDef;

/////////////////////////////////////////////////////

/**
 * A client to handle operations on the OMERO server
 *
 * This client can connect to the omero server.
 *
 * @since: 1.0.0
 */
public class BasicOMEROClient {

  //////////////////
  private final String hostname;
  private final int port;
  private final String username;
  private final String password;
  private final Gateway gateway;
  private final int serverId;
  //////////////////
  private String sessionId;
  private String sessionUuid;
  private SecurityContext securityContext;


  private HashMap<Long, String> projectMap;
  private HashMap<Long, Set<DatasetData>> datasetMap;

  public BasicOMEROClient(String username, String password, String hostname, int port) {

    this.username = username;
    this.password = password;
    this.hostname = hostname;
    this.port = port;
    this.serverId = 1;

    this.sessionId = null;
    this.sessionUuid = null;
    this.securityContext = null;

    this.gateway = new Gateway(new SimpleLogger());
  }

  /**
   * This method returns true if a connection to OMERO exists.
   *
   * @return true when a connection to the OMERO server exists. false otherwise
   * @see Gateway#isConnected()
   * @since 1.2.0
   */
  public boolean isConnected() {
    if (sessionId != null && sessionUuid != null && securityContext != null && this.gateway.isConnected()) {
      return true;
    } else {
      if (this.gateway.isConnected()) {
        throw new IllegalStateException("Omero client is in an illegal connection state.");
      } else {
        return false;
      }
    }
  }

  /**
   * Connects with the provided username and password.
   * Existing connections are severed in favor of the new connection.
   * @param username The username to log into OMERO
   * @param password a password associated to the given username
   * @param hostname the OMERO hostname
   * @param port the port at which the OMERO server can be reached
   */
  private void connect(String username, String password, String hostname, int port) {

    if (this.isConnected()) {
      this.disconnect();
    }

    LoginCredentials loginCredentials = new LoginCredentials(username, password, hostname, port);

    try {
      ExperimenterData user = this.gateway.connect(loginCredentials);
      this.securityContext = new SecurityContext(user.getGroupId());
      this.sessionId = gateway.getSessionId(user);
      this.sessionUuid = gateway.getAdminService(securityContext).getEventContext().sessionUuid;
    } catch (DSOutOfServiceException dsOutOfServiceException) {
      throw new RuntimeException("Error while accessing omero service: broken connection, expired session or not logged in", dsOutOfServiceException);
    } catch (ServerError serverError) {
      throw new RuntimeException("Omero store interaction failed.", serverError);
    }
  }

  /**
   * Tries to connect to an existing session with provided uuid.
   * If a connection to the desired session exists, nothing will be done.
   * If a connection to another session exists, the connection will be closed and a new connection
   * to the desired UUID will be established.
   *
   * @param sessionUuid the UUID of the session a connection should be established to
   * @since 1.2.0
   */
  private void connect(String sessionUuid) {
    if (this.isConnected()) {
      try {
        if (!this.gateway
            .getAdminService(securityContext)
            .getEventContext()
            .sessionUuid
            .equals(sessionUuid)) {
          return;
        }
      } catch (ServerError | DSOutOfServiceException exception) {
        throw new RuntimeException("Reconnecting failed. Keeping current connection.", exception);
      }
    }
    this.disconnect();
    this.connect(sessionUuid, "", this.hostname, this.port);
  }

  /**
   * Connects to the omero gateway.
   *
   * @see Gateway
   */
  public void connect() {
    if (this.isConnected()) {
      return;
    }
    this.connect(this.username, this.password, this.hostname, this.port);
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

    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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

    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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

  /**
   * This method closes the current connection and invalidates the corresponding OMERO session.
   * @see Gateway#disconnect()
   */
  public void disconnect() {
    this.gateway.disconnect();
    this.sessionId = null;
    this.sessionUuid = null;
    this.securityContext = null;
  }

  /**
   * Returns a filepath to the requested image file in ome.tiff format. If no ome.tiff is
   * available the path to the tiff of the image will be returned
   *
   * @param imageId the omero ID for the requested image
   * @return a filepath to the ome.tiff of an image
   */
  public String downloadOmeTiff(long imageId) throws IOException {
    if (!this.isConnected()) {
      this.connect();
    }
    String imagePath = "";
    try {
      final String omeTiffFormat = "OMETiff";
      // find image
      BrowseFacility browser = this.gateway.getFacility(BrowseFacility.class);
      ImageData imageData = browser.getImage(securityContext, imageId);
      // The format .ome.tiff always is expected to be present otherwise exception
      if (imageData.getFormat().equals(omeTiffFormat)) {
        // the given imagedata has the desired format
        imagePath = getImageDownloadLink(imageId);
      } else {
        // We want to always provide an ome tiff.

        // Every image should have an attachment/annotation that contains a corresponding .ome.tiff
        List<FileAnnotationData> fileAnnotations = fetchFileAnnotationDataForImage(imageId);
        for (FileAnnotationData fileAnnotation : fileAnnotations) {
          System.out.println("iterating through annotations " + fileAnnotation.getFileID());
          if (fileAnnotation.getFileFormat().equals(omeTiffFormat) || fileAnnotation.getFileName().equals("Batch_Image_Export.zip")) {
            // we found an annotation with the desired format
            imagePath = getAnnotationFileDownloadLink(fileAnnotation.getId());
            System.out.println(imagePath);
            break;
          } else {
            // we don't have a file annotation with the desired format
            imagePath = getImageDownloadLink(imageId); //just download the image in any format it is stored?
            //TODO decide what to do
          }
        }
        System.out.println("Finished searching for image with format " + omeTiffFormat);
      }
    } catch (DSOutOfServiceException | ExecutionException | DSAccessException e) {
      throw new RuntimeException("The file could not be downloaded",e);
    }
    return imagePath;
  }


  /**
   * Tries to build an image download link for a given imageID. An exception will be thrown if the
   * image can not be downloaded due to its format
   *
   * @param imageID the omero identifier of the desired image
   * @return URL String to download the image or null
   */
  public String getImageDownloadLink(long imageID) {
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

    String downloadLinkAddress;
    try {
      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      ImageData image = browse.getImage(this.securityContext, imageID);
      if (image.getFormat() != null) {
        downloadLinkAddress =
            "http://"
                + hostname
                + "/omero/webgateway/archived_files/download/"
                + imageID
                + "?server="
                + serverId
                + "&bsession="
                + sessionUuid;
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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

    return "http://"
        + hostname
        + "/omero/webclient/annotation/"
        + annotationID
        + "?server="
        + serverId
        + "&bsession="
        + sessionUuid;
  }

  public HashMap<Long, String> loadProjects() {
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

    return "http://"
            + hostname
            + "/omero/webclient/img_detail/"
            + imageId
            + "/?server="
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
    // we need to be connected to OMERO otherwise the Gateway cannot retrieve information
    if (!this.isConnected()){
      connect();
    }

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

  /**
   * The destructor has to make sure to disconnect from the OMERO server and close the session.
   * @throws Throwable
   * @since 1.2.0
   */
  @Override
  protected void finalize() throws Throwable {
    this.disconnect();
    super.finalize();
  }
}
