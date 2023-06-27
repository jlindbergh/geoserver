/* (c) 2023 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wps.longitudinal;

import static org.locationtech.jts.densify.Densifier.densify;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.measure.Unit;
import javax.measure.UnitConverter;
import javax.measure.quantity.Length;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.config.GeoServer;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;
import org.geotools.util.logging.Logging;
import org.jaitools.jts.CoordinateSequence2D;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import si.uom.SI;

@DescribeProcess(
        title = "Longitudinal Profile Process",
        description =
                "The process splits provided linestring to segments, that are no bigger then distance parameter, "
                        + "then evaluates altitude for each point and builds longitudinal profile. "
                        + "Altitude will be adjusted if adjustment layer is provided as parameter. "
                        + "Also supports reprojection to different crs")
public class LongitudinalProfileProcess implements GeoServerProcess {

    static final Logger LOGGER = Logging.getLogger(LongitudinalProfileProcess.class);

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String SEP = System.lineSeparator();

    private final GeoServer geoServer;

    private static final CoordinateReferenceSystem meterCrs;

    public LongitudinalProfileProcess(GeoServer geoServer) {
        this.geoServer = geoServer;
    }

    static {
        try {
            meterCrs = CRS.decode("EPSG:3857");
        } catch (FactoryException e) {
            throw new RuntimeException(e);
        }
    }

    @DescribeResult(
            name = "result",
            description = "Longitudinal Profile Process result.",
            meta = {"mimeTypes=application/json"},
            type = LongitudinalProfileProcessResult.class)
    public LongitudinalProfileProcessResult execute(
            @DescribeParameter(name = "layerName", description = "Input raster name", min = 1)
                    String layerName,
            @DescribeParameter(
                            name = "adjustmentLayerName",
                            description = "adjustment layer name",
                            min = 0)
                    String adjustmentLayerName,
            @DescribeParameter(name = "linestringEWKT", description = "linestring ewkt", min = 1)
                    String linestringEWkt,
            @DescribeParameter(name = "distance", description = "distance between points", min = 1)
                    Double distance,
            @DescribeParameter(name = "targetProjection", description = "projection for result", min = 0)
                    CoordinateReferenceSystem projection,
            @DescribeParameter(
                            name = "altitudeIndex",
                            description = "index of altitude in coordinate array",
                            min = 0,
                            defaultValue = "0")
                    Integer altitudeIndex,
            @DescribeParameter(
                            name = "altitudeName",
                            description = "name of altitude attribute on adjustment layer",
                            min = 0)
                    String altitudeName)
            throws IOException, FactoryException, TransformException, CQLException {

        long startTime = System.currentTimeMillis();
        LOGGER.info(
                "Starting processing at:"
                        + startTime
                        + " with params: "
                        + SEP
                        + " layer name: "
                        + layerName
                        + SEP
                        + " adjustment layer name: "
                        + adjustmentLayerName
                        + SEP
                        + " linestring ewkt: "
                        + linestringEWkt
                        + SEP
                        + " distance: "
                        + distance
                        + SEP
                        + " altitude index: "
                        + altitudeIndex
                        + SEP
                        + " altitude name: "
                        + altitudeName);
        if (projection != null) {
            LOGGER.info(" targetProjection: " + projection.getName());
        }

        LineString lineString = ECQL.toExpression(linestringEWkt).evaluate(null, LineString.class);

        if (projection == null) {
            projection = (CoordinateReferenceSystem) lineString.getUserData();
        }

        CoverageInfo coverageInfo = geoServer.getCatalog().getCoverageByName(layerName);

        FeatureSource featureSource = getAdjustmentLayerFeatureSource(adjustmentLayerName);

        GridCoverage2DReader gridCoverageReader =
                (GridCoverage2DReader) coverageInfo.getGridCoverageReader(null, null);
        GridCoverage2D gridCoverage2D = gridCoverageReader.read(null);

        Geometry denseLine = densifyLine(distance, lineString);

        if (!coverageInfo.getCRS().equals(lineString.getUserData())) {
            denseLine =
                    reprojectGeometry(
                            ((CoordinateReferenceSystem) lineString.getUserData()),
                            coverageInfo.getCRS(),
                            denseLine);
        }

        List<ProfileInfo> profileInfos = new ArrayList<>();
        double positiveAltitude = 0;
        double negativeAltitude = 0;
        double previousAltitude = 0;
        double totalDistance = 0;
        Geometry previousPoint = null;

        CoordinateReferenceSystem coverageCrs = coverageInfo.getCRS();
        List<DirectPosition2D> positions2D =
                Arrays.stream(denseLine.getCoordinates())
                        .map(
                                coordinate ->
                                        new DirectPosition2D(
                                                coverageCrs, coordinate.x, coordinate.y))
                        .collect(Collectors.toList());

        for (DirectPosition2D position2D : positions2D) {
            LOGGER.info("processing position:" + position2D);
            CoordinateSequence2D coordinateSequence =
                    new CoordinateSequence2D(position2D.getX(), position2D.getY());
            Geometry point = new Point(coordinateSequence, GEOMETRY_FACTORY);

            double pointAltitude =
                    getAltitude(
                            featureSource,
                            gridCoverage2D,
                            position2D,
                            point,
                            altitudeIndex,
                            altitudeName);

            double profileAltitude = pointAltitude - previousAltitude;
            if (projection != null) {
                point = reprojectGeometry(coverageCrs, projection, point);
            }

            Coordinate coordinate = point.getCoordinate();

            double slope = 0;
            ProfileInfo currentInfo;
            if (previousPoint == null) {
                currentInfo =
                        new ProfileInfo(
                                0, coordinate.getX(), coordinate.getY(), pointAltitude, slope);
            } else {
                double distanceToPrevious = point.distance(previousPoint);

                totalDistance += distanceToPrevious;
                slope = calculateSlope(projection, previousPoint, point, pointAltitude);
                currentInfo =
                        new ProfileInfo(
                                totalDistance,
                                coordinate.getX(),
                                coordinate.getY(),
                                pointAltitude,
                                slope);
            }
            if (profileAltitude >= 0) {
                positiveAltitude += profileAltitude;
            } else {
                negativeAltitude += profileAltitude;
            }

            previousAltitude = pointAltitude;

            profileInfos.add(currentInfo);
            previousPoint = point;
        }

        OperationInfo operationInfo =
                buildOperationInfo(
                        layerName,
                        startTime,
                        profileInfos,
                        positiveAltitude,
                        negativeAltitude,
                        totalDistance);

        return new LongitudinalProfileProcessResult(profileInfos, operationInfo);
    }

    private Geometry densifyLine(Double distance, LineString lineString) {
        double distanceInTargetCrsUnits =
                metersToCrsUnits(
                        (CoordinateReferenceSystem) lineString.getUserData(),
                        lineString.getCentroid().getCoordinate(),
                        distance);
        Geometry denseLine = densify(lineString, distanceInTargetCrsUnits);
        return denseLine;
    }

    private static double calculateSlope(
            CoordinateReferenceSystem projection,
            Geometry previousPoint,
            Geometry point,
            double altitude)
            throws FactoryException, TransformException {
        return altitude * 100 / distanceInMeters(projection, previousPoint, point);
    }

    /**
     * Attempts to calculate distance between 2 points in meters. If CRS is not using meters, then
     * attempting to reproject points to EPSG:3857 which supports them
     *
     * @param projection
     * @param previousPoint
     * @param point
     * @return
     * @throws FactoryException
     * @throws TransformException
     */
    private static double distanceInMeters(
            CoordinateReferenceSystem projection, Geometry previousPoint, Geometry point)
            throws FactoryException, TransformException {
        double distanceToPrevious;
        if (projection != null && "WGS 84".equals(projection.getName().getCode())) {
            // In order to get distance in meters we will reproject points to EPSG:3857
            Geometry previousCoordinate = reprojectGeometry(projection, meterCrs, previousPoint);
            Geometry currentCoordinate = reprojectGeometry(projection, meterCrs, point);
            distanceToPrevious = currentCoordinate.distance(previousCoordinate);
        } else {
            distanceToPrevious = point.distance(previousPoint);
        }
        return distanceToPrevious;
    }

    private static double getAltitude(
            FeatureSource featureSource,
            GridCoverage2D gridCoverage2D,
            DirectPosition2D position2D,
            Geometry point,
            int altitudeIndex,
            String altitudeName)
            throws IOException, CQLException {
        double altitude = calculateAltitude(gridCoverage2D.evaluate(position2D), altitudeIndex);
        // Round altitude
        altitude = BigDecimal.valueOf(altitude).setScale(2, RoundingMode.HALF_UP).doubleValue();

        if (featureSource != null) {
            altitude = getAdjustedAltitude(featureSource, point, altitude, altitudeName);
        }
        return altitude;
    }

    private static OperationInfo buildOperationInfo(
            String layerName,
            long startTime,
            List<ProfileInfo> profileInfos,
            double positiveAltitude,
            double negativeAltitude,
            double totalDistance) {
        OperationInfo operationInfo = new OperationInfo();
        operationInfo.setTotalDistance(totalDistance);
        operationInfo.setProcessedPoints(profileInfos.size());
        ProfileInfo firstProfile = profileInfos.get(0);
        operationInfo.setFirstPointX(firstProfile.getX());
        operationInfo.setFirstPointY(firstProfile.getY());

        ProfileInfo lastProfile = profileInfos.get(profileInfos.size() - 1);
        operationInfo.setLastPointX(lastProfile.getX());
        operationInfo.setLastPointY(lastProfile.getY());
        operationInfo.setAltitudePositive(positiveAltitude);
        operationInfo.setAltitudeNegative(negativeAltitude);
        operationInfo.setLayer(layerName);

        operationInfo.setExecutedTime(System.currentTimeMillis() - startTime);
        return operationInfo;
    }

    private static Geometry reprojectGeometry(
            CoordinateReferenceSystem source, CoordinateReferenceSystem target, Geometry geometry)
            throws FactoryException, TransformException {
        MathTransform tx = CRS.findMathTransform(source, target, true);

        return JTS.transform(geometry, tx);
    }

    /**
     * Process altitude using adjustment layer. Method attempts to find feature that contains
     * provided parameter point geometry, and if found, subtracts its altitude value from provided
     * parameter altitude
     *
     * @param featureSource
     * @param geometry
     * @param altitude
     * @return
     * @throws IOException
     */
    private static double getAdjustedAltitude(
            FeatureSource featureSource, Geometry geometry, double altitude, String altitudeName)
            throws IOException, CQLException {
        Query query;
        Filter filter;
        Coordinate coordinate = geometry.getCoordinate();
        filter =
                CQL.toFilter(
                        "CONTAINS(the_geom, POINT("
                                + coordinate.getX()
                                + " "
                                + coordinate.getY()
                                + "))");
        query = new Query(featureSource.getSchema().getName().getLocalPart(), filter);
        try (FeatureIterator featureIterator = featureSource.getFeatures(query).features()) {
            if (featureIterator.hasNext()) {
                Feature feature = featureIterator.next();
                Double adjLayerAltitude = (Double) feature.getProperty(altitudeName).getValue();
                altitude = altitude - adjLayerAltitude;
            }
        }
        return altitude;
    }

    private FeatureSource<? extends FeatureType, ? extends Feature> getAdjustmentLayerFeatureSource(
            String adjustmentLayerName) throws IOException {
        FeatureSource<? extends FeatureType, ? extends Feature> featureSource = null;
        if (adjustmentLayerName != null && !adjustmentLayerName.isBlank()) {
            LayerInfo adjustmentLayer = geoServer.getCatalog().getLayerByName(adjustmentLayerName);
            FeatureTypeInfo resource = (FeatureTypeInfo) adjustmentLayer.getResource();
            featureSource = resource.getFeatureSource(null, null);
        }
        return featureSource;
    }

    private static double calculateAltitude(Object obj, int altitudeIndex) {
        Class<?> objectClass = obj.getClass();
        if (objectClass.isArray()) {
            switch (objectClass.getComponentType().getName()) {
                case "byte":
                    return ((byte[]) obj)[altitudeIndex];
                case "int":
                    return ((int[]) obj)[altitudeIndex];
                case "float":
                    return ((float[]) obj)[altitudeIndex];
                case "double":
                    return ((double[]) obj)[altitudeIndex];
                default:
                    // Do nothing
            }
        }
        throw new IllegalArgumentException();
    }

    private double metersToCrsUnits(
            CoordinateReferenceSystem crs, Coordinate centroidCoord, double distanceInMeters) {
        if (crs instanceof GeographicCRS) {
            double sizeDegree = 110574.2727;
            if (centroidCoord != null) {
                double cosLat = Math.cos(Math.PI * centroidCoord.y / 180.0);
                double latAdjustment = Math.sqrt(1 + cosLat * cosLat) / Math.sqrt(2.0);
                sizeDegree *= latAdjustment;
            }
            return distanceInMeters / sizeDegree;
        } else {
            @SuppressWarnings("unchecked")
            Unit<Length> unit = (Unit<Length>) crs.getCoordinateSystem().getAxis(0).getUnit();
            if (unit == null) {
                return distanceInMeters;
            } else {
                UnitConverter converter = SI.METRE.getConverterTo(unit);
                return converter.convert(distanceInMeters);
            }
        }
    }

    /** Object for storing result of longitudinal profile WPS process */
    public static final class LongitudinalProfileProcessResult {

        public LongitudinalProfileProcessResult(
                List<ProfileInfo> profileInfoList, OperationInfo operationInfo) {
            this.profileInfoList = profileInfoList;
            this.operationInfo = operationInfo;
        }

        @JsonProperty("profile")
        private List<ProfileInfo> profileInfoList;

        @JsonProperty("infos")
        private OperationInfo operationInfo;

        public List<ProfileInfo> getProfileInfoList() {
            return profileInfoList;
        }

        public OperationInfo getOperationInfo() {
            return operationInfo;
        }
    }
}