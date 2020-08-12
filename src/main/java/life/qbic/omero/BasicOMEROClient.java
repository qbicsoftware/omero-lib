package life.qbic.omero;

import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.DataManagerFacility;
import omero.gateway.facility.MetadataFacility;
import omero.ServerError;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.model.*;
import omero.log.SimpleLogger;
import omero.model.*;
import omero.romio.PlaneDef;
import java.util.*;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;



/////////////////////////////////////////////////////

public class BasicOMEROClient {

  //////////////////
  /** Reference to the gateway. */
  private Gateway gateway;
  /** The security context. */
  private SecurityContext ctx;

  private String hostname;
  private int port;

  private String username;
  private String password;

  private String sessionId;

  private HashMap<Long, String> projectMap;
  private HashMap<Long, Set<DatasetData>> datasetMap;

  public BasicOMEROClient(String username, String password, String hostname, int port) {

    this.username = username;
    this.password = password;
    this.hostname = hostname;
    this.port = port;

    this.sessionId = "";

  }

  private void connect(String hostname, int port, String username, String password)
      throws Exception {

    this.gateway = new Gateway(new SimpleLogger());
    LoginCredentials cred = new LoginCredentials();
    cred.getServer().setHostname(hostname);

    if (port > 0) {
      cred.getServer().setPort(port);
    }

    cred.getUser().setUsername(username);
    cred.getUser().setPassword(password);

    ExperimenterData user = gateway.connect(cred);
    this.ctx = new SecurityContext(user.getGroupId());

    this.sessionId = gateway.getSessionId(user);
  }

  public void connect() {
    try {

      this.connect(this.hostname, this.port, this.username, this.password);
    } catch (Exception e) {
      System.out.println(e);

      System.out.println("++++++++++++++++++++++++++++++++++++++");
      System.out.println(e.getLocalizedMessage());
      System.out.println(e.getMessage());
      System.out.println(e.toString());

    }

  }

  /**
   * Returns any file annotations (information about attachments) of a given image
   * 
   * @param imageID the ID of the image
   * @return A list of FileAnnotationData objects
   * @throws DSOutOfServiceException
   * @throws DSAccessException
   * @throws ExecutionException
   */
  public List<FileAnnotationData> fetchFileAnnotationDataForImage(long imageID)
      throws DSOutOfServiceException, DSAccessException, ExecutionException {
    List<FileAnnotationData> annotations = new ArrayList<>();
    for (AnnotationData d : loadAnnotationsForImage(imageID, FileAnnotationData.class)) {
      annotations.add((FileAnnotationData) d);
    }
    return annotations;
  }

  /**
   * Returns any map annotation data (key value pairs of metadata) of a given image
   * 
   * @param imageID the ID of the image
   * @return A list of MapAnnotationData objects
   * @throws DSOutOfServiceException
   * @throws DSAccessException
   * @throws ExecutionException
   */
  public List<MapAnnotationData> fetchMapAnnotationDataForImage(long imageID)
      throws DSOutOfServiceException, DSAccessException, ExecutionException {
    List<MapAnnotationData> annotations = new ArrayList<>();
    for (AnnotationData d : loadAnnotationsForImage(imageID, MapAnnotationData.class)) {
      annotations.add((MapAnnotationData) d);
    }
    return annotations;
  }

  private Collection<? extends AnnotationData> loadAnnotationsForImage(long imageID,
      Class<? extends AnnotationData> type)
      throws DSOutOfServiceException, DSAccessException, ExecutionException {
    long userID = this.gateway.getLoggedInUser().getId();

    BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
    ImageData image = browse.getImage(this.ctx, imageID);

    List<Long> userIds = new ArrayList<Long>();
    userIds.add(userID);

    List<Class<? extends AnnotationData>> types = new ArrayList<Class<? extends AnnotationData>>();
    types.add(type);

    MetadataFacility metadata = gateway.getFacility(MetadataFacility.class);
    List<AnnotationData> annotations = metadata.getAnnotations(ctx, image, types, userIds);

    disconnect();

    if (annotations != null) {
      return annotations;
    }
    return new ArrayList<>();
  }


  /**
   * render buffered image of image object in Omero
   * 
   * @param image imageData object from Omero
   * @param zPLane selected slide of the vertical axis of a 3D image, else 0
   * @param tPlane selected time "plane" of a time series, else 0
   * @return
   * @throws ServerError
   * @throws DSOutOfServiceException interrupted or broken connection to the server
   * @throws IOException
   */
  public BufferedImage renderImage(ImageData image, int zPlane, int tPlane)
      throws ServerError, DSOutOfServiceException, IOException {

    BufferedImage res = null;

    PixelsData pixels = image.getDefaultPixels();
    long pixelsId = pixels.getId();
    RenderingEnginePrx proxy = null;
    proxy = gateway.getRenderingService(ctx, pixelsId);
    ByteArrayInputStream stream = null;
    try {
      proxy.lookupPixels(pixelsId);
      if (!(proxy.lookupRenderingDef(pixelsId))) {
        proxy.resetDefaultSettings(true);
        proxy.lookupRenderingDef(pixelsId);
      }
      proxy.load();
      // Now can interact with the rendering engine.
      proxy.setActive(0, Boolean.valueOf(false));
      PlaneDef pDef = new PlaneDef();
      pDef.z = zPlane;
      pDef.t = tPlane;
      pDef.slice = omero.romio.XY.value;
      // render the data uncompressed.
      int[] uncompressed = proxy.renderAsPackedInt(pDef);
      byte[] compressed = proxy.renderCompressed(pDef);
      // Create a buffered image
      stream = new ByteArrayInputStream(compressed);

      res = ImageIO.read(stream);
    } finally {
      proxy.close();
      if (stream != null)
        stream.close();
    }
    return res;
  }

  private Gateway getGateway() {
    return gateway;
  }

  private SecurityContext getContext() {
    return ctx;
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

  public HashMap<Long, String> loadProjects() {

    this.projectMap = new HashMap<Long, String>();
    this.datasetMap = new HashMap<Long, Set<DatasetData>>();

    try {

      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      Collection<ProjectData> projects = browse.getProjects(ctx);

      Iterator<ProjectData> i = projects.iterator();
      ProjectData project;
      while (i.hasNext()) {
        project = i.next();

        String name = project.getName();
        long id = project.getId();

        this.projectMap.put(id, name);
        this.datasetMap.put(id, project.getDatasets());
      }

    } catch (Exception e) {
      System.out.println(e);
    }

    return this.projectMap;
  }

  public HashMap<String, String> getProjectInfo(long projectId) {

    HashMap<String, String> projectInfo = new HashMap<String, String>();

    try {

      BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
      Collection<ProjectData> projects = browse.getProjects(ctx);

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

    } catch (Exception e) {
      System.out.println(e);
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

    try {

      DataManagerFacility dm = gateway.getFacility(DataManagerFacility.class);

      Project proj = new ProjectI();
      proj.setName(omero.rtypes.rstring(name));
      proj.setDescription(omero.rtypes.rstring(desc));

      IObject r = dm.saveAndReturnObject(this.ctx, proj);

      return r.getId().getValue();

    } catch (Exception e) {
      System.out.println(e);
      return -1;
    }


  }

  public long createDataset(long projectId, String name, String desc) {

    try {

      DataManagerFacility dm = gateway.getFacility(DataManagerFacility.class);

      Dataset dataset = new DatasetI();
      dataset.setName(omero.rtypes.rstring(name));
      dataset.setDescription(omero.rtypes.rstring(desc));
      ProjectDatasetLink link = new ProjectDatasetLinkI();
      link.setChild(dataset);
      link.setParent(new ProjectI(projectId, false));

      IObject r = dm.saveAndReturnObject(this.ctx, link);

      ProjectDatasetLink remote_link = (ProjectDatasetLink) r;
      return remote_link.getChild().getId().getValue();


    } catch (Exception e) {
      System.out.println(e);
      return -1;
    }


  }

  public void addMapAnnotationToProject(long projectId, String key, String value) {

    List<NamedValue> result = new ArrayList<NamedValue>();
    result.add(new NamedValue(key, value));

    MapAnnotationData data = new MapAnnotationData();
    data.setContent(result);
    // data.setDescription("Training Example");

    // Use the following namespace if you want the annotation to be editable
    // in the webclient and insight
    data.setNameSpace(MapAnnotationData.NS_CLIENT_CREATED);
    try {

      DataManagerFacility fac = gateway.getFacility(DataManagerFacility.class);
      fac.attachAnnotation(ctx, data, new ProjectData(new ProjectI(projectId, false)));

    } catch (Exception e) {
      System.out.println(e);
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
      fac.attachAnnotation(ctx, data, new DatasetData(new DatasetI(datasetId, false)));

    } catch (Exception e) {
      System.out.println(e);
    }

  }

  public HashMap<Long, String> getImages(long datasetId) {

    HashMap<Long, String> imageList = new HashMap<Long, String>();

    try {

      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images = browse.getImagesForDatasets(ctx, Arrays.asList(datasetId));

      Iterator<ImageData> j = images.iterator();
      ImageData image;
      while (j.hasNext()) {
        image = j.next();
        imageList.put(image.getId(), image.getName());

      }

    } catch (Exception e) {
      System.out.println(e);
    }

    return imageList;
  }

  public HashMap<String, String> getImageInfo(long datasetId, long imageId) {

    HashMap<String, String> imageInfo = new HashMap<String, String>();

    try {

      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images =
          browse.getImagesForDatasets(this.ctx, Arrays.asList(datasetId));

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
        List<ChannelData> data = mdf.getChannelData(ctx, imageId);
        for (ChannelData c : data) {
          channelNamesString = channelNamesString + c.getName() + ", ";
        }
        channelNamesString = channelNamesString.substring(0, channelNamesString.length() - 2);

        imageInfo.put("channels", channelNamesString);
      }



    } catch (Exception e) {
      System.out.println(e);
    }

    return imageInfo;
  }

  public ByteArrayInputStream getThumbnail(long datasetId, long imageId) throws Exception {

    // BufferedImage img = null;
    ThumbnailStorePrx store = null;
    ByteArrayInputStream stream = null;

    try {

      BrowseFacility browse = this.gateway.getFacility(BrowseFacility.class);
      Collection<ImageData> images =
          browse.getImagesForDatasets(this.ctx, Arrays.asList(datasetId));

      Iterator<ImageData> j = images.iterator();
      ImageData image = null;
      while (j.hasNext()) {
        image = j.next();
        if (image.getId() == imageId) {
          break;
        }
      }

      store = this.gateway.getThumbnailService(ctx);

      PixelsData pixels = image.getDefaultPixels();
      store.setPixelsId(pixels.getId());
      byte[] array = store.getThumbnail(omero.rtypes.rint(96), omero.rtypes.rint(96));
      stream = new ByteArrayInputStream(array);
      // img = ImageIO.read(stream);

    } catch (Exception e) {
      System.out.println(e);
    } finally {
      if (store != null)
        store.close();
    }

    return stream; // img;

  }

}
