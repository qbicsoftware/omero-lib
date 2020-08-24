# Changelog for the omero client library

## 1.2.0
* Throws RuntimeExceptions instead of Checked exceptions
* Fetches session uuid from omero
* Generates complete links for web image viewer
* Generates complete links for web image download
* BasicOMEROClient always holds 1 session at most
* BasicOMEROClient can generate `.ome.tiff` images

## 1.1.0 - 12-08-2020

### New Features
* Provide a method to obtain download link for a stored image
* Provide option to fetch metadata associated with a given image
* Provide a method to render a stored image into a BufferedImage object

### Fixes
* None
