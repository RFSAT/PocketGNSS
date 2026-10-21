# Geoid grids for the tests

Four PROJ-data grids (cdn.proj.org), unchanged, to test the app's GeoTIFF reader:

* `at_bev_GEOID_GRS80_Oesterreich.tif`: BEV Austria, CC BY 4.0 (strips).
* `be_ign_hBG18.tif`: hBG18, IGN Belgium, CC BY 4.0 (tiles).
* `lv_lgia_lv14.tif`: LV'14, Latvian Geospatial Information Agency, CC BY 4.0 (no-data value).
* `us_nga_egm96_15.tif`: EGM96 15', NGA, public domain (global grid, longitude wrap).

`refpoints.txt`: random points in every height model the app offers (23 grids) with the geoid undulation N computed by
PROJ 9.8.1 with the EPSG transformation of each grid (H = h - N). GeoidTest checks the four grids above; with the
system property `geoid.dir` pointing to a folder with all 23 grids it checks them all.
