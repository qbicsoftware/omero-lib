package life.qbic.omero

import omero.*
import ome.tools.hibernate.SessionFactory
import omero.api.ServiceFactoryPrx
import omero.model.Session
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

  def "make sure codecov has anything to return"() {
    expect:
    1 + 1 == 2
  }
}
