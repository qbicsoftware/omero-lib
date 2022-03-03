omero-lib
-----------------------------------

|maven-build| |maven-test| |codeql| |release|
|license| |java|

OMERO client library - A Java-based library to access an OMERO server

How to Run
-----------------

To build this library use Maven and Java 8:

First compile the project and build an executable java archive:

.. code-block:: bash

    mvn clean package

Note that you will need java 8.
The JAR file will be created in the /target folder:

.. code-block:: bash

    |-target
    |---omero-client-lib-<version>.jar
    |---...

How to Use
----------

This is a library and the most common way to use this library in particular is by including it in your `pom.xml` as a dependency:

.. code-block:: xml

    <dependency>
      <groupId>life.qbic</groupId>
      <artifactId>omero-client-lib</artifactId>
      <version>X.Y.Z</version>
    </dependency>

License
-------

This work is licensed under the `MIT license <https://mit-license.org/>`_.
**Note**: This work uses the `Open Microscopy Environment Framework <https://github.com/ome>`_ and derivatives from the Omero framework family, which are licensed under `GNU General Public License (GPL) <https://www.gnu.org/licenses/old-licenses/lgpl-2.0.html>`_.


.. |maven-build| image:: https://github.com/qbicsoftware/omero-lib/workflows/Build%20Maven%20Package/badge.svg
    :target: https://github.com/qbicsoftware/omero-lib/actions/workflows/build_package.yml
    :alt: Github Workflow Build Maven Package Status

.. |maven-test| image:: https://github.com/qbicsoftware/omero-lib/workflows/Run%20Maven%20Tests/badge.svg
    :target: https://github.com/qbicsoftware/omero-lib/actions/workflows/run_tests.yml
    :alt: Github Workflow Tests Status

.. |codeql| image:: https://github.com/qbicsoftware/omero-lib/workflows/CodeQL/badge.svg
    :target: https://github.com/qbicsoftware/omero-lib/actions/workflows/codeql-analysis.yml
    :alt: CodeQl Status

.. |license| image:: https://img.shields.io/github/license/qbicsoftware/omero-lib
    :target: https://github.com/qbicsoftware/omero-lib/blob/master/LICENSE
    :alt: Project Licence

.. |release| image:: https://img.shields.io/github/v/release/qbicsoftware/omero-lib.svg?include_prereleases
    :target: https://github.com/qbicsoftware/omero-lib/release
    :alt: Release status

.. |java| image:: https://img.shields.io/badge/language-java-blue.svg
    :alt: Written in Java
