package life.qbic.omero

import org.apache.log4j.lf5.viewer.configure.ConfigurationManager
import spock.lang.Specification

/**
 * <class short description - 1 Line!>
 *
 * <More detailed description - When to use, what it solves, etc.>
 *
 * @author Sven Fillinger
 * @since <versiontag>
 */
class BasicOMEROClientSpec extends Specification {

  //TODO find a way to make it work this don't work.
  private final ConfigurationManager cm = ConfigurationManagerFactory.getInstance();

  BasicOMEROClient omeroClient = new BasicOMEROClient(cm.getOmeroUser(), cm.getOmeroPassword(), cm.getOmeroHostname(), Integer.parseInt(cm.getOmeroPort()));

  def "GetImageDownloadLink"() {
    given: "a connected omero client"
    long imageId = 4242;
    omeroClient.connect()
    when: "we compose the image detail viewer address"
    String address = omeroClient.composeImageDetailAddress(imageId);
    then: "a correct http address is returned"
    String correctAddress = "http://" + cm.getOmeroHostname() + "/omero/webclient/img_detail/" + imageId + "/?server=" + 1 + "&bsession="; // + session UUID
    address.startsWith(correctAddress)
  }

  def "make sure codecov has anything to return"() {
    expect:
    1 + 1 == 2
  }
}
