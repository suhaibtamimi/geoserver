/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.kml;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.geoserver.kml.decorator.LookAtDecoratorFactory;
import org.geoserver.ows.URLMangler.URLType;
import org.geoserver.ows.util.ResponseUtils;
import org.geoserver.platform.ServiceException;
import org.geoserver.wms.GetMapRequest;
import org.geoserver.wms.MapLayerInfo;
import org.geoserver.wms.MapProducerCapabilities;
import org.geoserver.wms.WMS;
import org.geoserver.wms.WMSMapContent;
import org.geoserver.wms.WMSRequests;
import org.geoserver.wms.map.AbstractMapOutputFormat;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.feature.FeatureCollection;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.Layer;
import org.geotools.referencing.CRS;
import org.geotools.styling.Style;
import org.geotools.util.logging.Logging;
import org.opengis.filter.Filter;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import de.micromata.opengis.kml.v_2_2_0.Document;
import de.micromata.opengis.kml.v_2_2_0.Kml;
import de.micromata.opengis.kml.v_2_2_0.LatLonAltBox;
import de.micromata.opengis.kml.v_2_2_0.Link;
import de.micromata.opengis.kml.v_2_2_0.Lod;
import de.micromata.opengis.kml.v_2_2_0.LookAt;
import de.micromata.opengis.kml.v_2_2_0.NetworkLink;
import de.micromata.opengis.kml.v_2_2_0.Region;
import de.micromata.opengis.kml.v_2_2_0.ViewRefreshMode;

/**
 * TODO: - handle superoverlay and caching
 * 
 * @author Andrea Aime - GeoSolutions
 * 
 */
public class NetworkLinkMapOutputFormat extends AbstractMapOutputFormat {
    static final Logger LOGGER = Logging.getLogger(NetworkLinkMapOutputFormat.class);

    /**
     * Official KMZ mime type, tweaked to output NetworkLink
     */
    static final String KML_MIME_TYPE = KMLMapOutputFormat.MIME_TYPE + ";mode=networklink";

    static final String KMZ_MIME_TYPE = KMZMapOutputFormat.MIME_TYPE + ";mode=networklink";

    public static final String[] OUTPUT_FORMATS = { KML_MIME_TYPE, KMZ_MIME_TYPE };

    private WMS wms;

    public NetworkLinkMapOutputFormat(WMS wms) {
        super(KML_MIME_TYPE, OUTPUT_FORMATS);
        this.wms = wms;
    }

    /**
     * Initializes the KML encoder. None of the map production is done here, it is done in
     * writeTo(). This way the output can be streamed directly to the output response and not
     * written to disk first, then loaded in and then sent to the response.
     * 
     * @param mapContent WMSMapContext describing what layers, styles, area of interest etc are to
     *        be used when producing the map.
     * @see org.geoserver.wms.GetMapOutputFormat#produceMap(org.geoserver.wms.WMSMapContent)
     */
    @SuppressWarnings("rawtypes")
    public KMLMap produceMap(WMSMapContent mapContent) throws ServiceException, IOException {
        GetMapRequest request = mapContent.getRequest();

        // restore normal kml types (no network link mode)
        if (NetworkLinkMapOutputFormat.KML_MIME_TYPE.equals(request.getFormat())) {
            request.setFormat(KMLMapOutputFormat.MIME_TYPE);
        } else {
            request.setFormat(KMZMapOutputFormat.MIME_TYPE);
        }

        // prepare kml, document and folder
        Kml kml = new Kml();
        Document document = kml.createAndSetDocument();
        Map formatOptions = request.getFormatOptions();
        String kmltitle = (String) formatOptions.get("kmltitle");
        document.setName(kmltitle);

        // check the superoverlay modes
        Boolean superoverlay = (Boolean) formatOptions.get("superoverlay");
        if (superoverlay == null) {
            superoverlay = Boolean.FALSE;
        }

        if (superoverlay) {
            encodeAsSuperOverlay(document, mapContent);
        } else {
            encodeAsOverlay(document, mapContent);
        }

        boolean kmz = request.getFormat().equals(KMZ_MIME_TYPE)
                || request.getFormat().equals(KMZMapOutputFormat.MIME_TYPE);
        String mime = kmz ? KMZMapOutputFormat.MIME_TYPE : KMLMapOutputFormat.MIME_TYPE;
        KMLMap map = new KMLMap(mapContent, null, kml, mime);
        map.setContentDispositionHeader(mapContent, ".kml");
        return map;
    }

    private void encodeAsSuperOverlay(Document container, WMSMapContent mapContent) {
        GetMapRequest request = mapContent.getRequest();
        boolean cachedMode = "cached".equals(KMLUtils.getSuperoverlayMode(request, wms));

        List<MapLayerInfo> layers = request.getLayers();
        List<ReferencedEnvelope> layerBounds = new ArrayList<ReferencedEnvelope>(mapContent
                .layers().size());
        computePerLayerQueryBounds(mapContent, layerBounds, null);
        List<Style> styles = request.getStyles();
        for (int i = 0; i < layers.size(); i++) {
            MapLayerInfo layer = layers.get(i);
            if (cachedMode && KMLUtils.isRequestGWCCompatible(request, i, wms)) {
                encodeGWCLink(container, request, layer);
            } else {
                String styleName = i < styles.size() ? styles.get(i).getName() : null;
                ReferencedEnvelope bounds = layerBounds.get(i);
                encodeLayerSuperOverlay(container, request, layer, styleName, i, bounds);
            }
        }
    }

    private void encodeLayerSuperOverlay(Document container, GetMapRequest request,
            MapLayerInfo layer, String styleName, int layerIndex, ReferencedEnvelope bounds) {
        NetworkLink nl = container.createAndAddNetworkLink();
        nl.setName(layer.getName());
        nl.setOpen(true);
        nl.setVisibility(true);

        // look at for the network link for this single layer
        if (bounds != null) {
            LookAtDecoratorFactory lookAtFactory = new LookAtDecoratorFactory();
            LookAtOptions lookAtOptions = new LookAtOptions(request.getFormatOptions());
            LookAt la = lookAtFactory.buildLookAt(bounds, lookAtOptions, false);
            nl.setAbstractView(la);
        }

        // region and lod
        Region region = nl.createAndSetRegion();
        LatLonAltBox box = region.createAndSetLatLonAltBox();
        box.setNorth(bounds.getMaxY());
        box.setSouth(bounds.getMinY());
        box.setEast(bounds.getMaxX());
        box.setWest(bounds.getMinX());

        Lod lod = region.createAndSetLod();
        lod.setMinLodPixels(128);
        lod.setMaxLodPixels(-1);

        // link
        Link link = nl.createAndSetLink();

        String href = WMSRequests.getGetMapUrl(request, layer.getName(), layerIndex, styleName,
                null, null);
        try {
            // WMSRequests.getGetMapUrl returns a URL encoded query string, but GoogleEarth
            // 6 doesn't like URL encoded parameters. See GEOS-4483
            href = URLDecoder.decode(href, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        link.setHref(href);
    }

    public void encodeGWCLink(Document container, GetMapRequest request, MapLayerInfo layer) {
        NetworkLink nl = container.createAndAddNetworkLink();
        String prefixedName = layer.getResource().prefixedName();
        nl.setName("GWC-" + prefixedName);
        Link link = nl.createAndSetLink();
        String type = layer.getType() == MapLayerInfo.TYPE_RASTER ? "png" : "kml";
        String url = ResponseUtils.buildURL(request.getBaseUrl(), "gwc/service/kml/" + prefixedName
                + "." + type + ".kml", null, URLType.SERVICE);
        link.setHref(url);
        link.setViewRefreshMode(ViewRefreshMode.NEVER);
    }

    private void encodeAsOverlay(Document container, WMSMapContent mapContent) {
        GetMapRequest request = mapContent.getRequest();
        Map formatOptions = request.getFormatOptions();
        LookAtDecoratorFactory lookAtFactory = new LookAtDecoratorFactory();
        LookAtOptions lookAtOptions = new LookAtOptions(formatOptions);

        // compute the layer bounds and the total bounds
        List<ReferencedEnvelope> layerBounds = new ArrayList<ReferencedEnvelope>(mapContent
                .layers().size());
        ReferencedEnvelope aggregatedBounds = computePerLayerQueryBounds(mapContent, layerBounds,
                null);
        if (aggregatedBounds != null) {
            LookAt la = lookAtFactory.buildLookAt(aggregatedBounds, lookAtOptions, false);
            container.setAbstractView(la);
        }

        final List<MapLayerInfo> layers = request.getLayers();
        final List<Style> styles = request.getStyles();
        for (int i = 0; i < layers.size(); i++) {
            MapLayerInfo layerInfo = layers.get(i);
            NetworkLink nl = container.createAndAddNetworkLink();
            nl.setName(layerInfo.getName());
            nl.setVisibility(true);
            nl.setOpen(true);

            // look at for this layer
            ReferencedEnvelope latLongBoundingBox = layerBounds.get(i);
            if (latLongBoundingBox != null) {
                LookAt la = lookAtFactory.buildLookAt(latLongBoundingBox, lookAtOptions, false);
                nl.setAbstractView(la);
            }

            // set bbox to null so its not included in the request, google
            // earth will append it for us
            request.setBbox(null);

            String style = i < styles.size() ? styles.get(i).getName() : null;
            String href = WMSRequests.getGetMapUrl(request, layers.get(i).getName(), i, style,
                    null, null);
            try {
                // WMSRequests.getGetMapUrl returns a URL encoded query string, but GoogleEarth
                // 6 doesn't like URL encoded parameters. See GEOS-4483
                href = URLDecoder.decode(href, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }

            Link url = nl.createAndSetUrl();
            url.setHref(href);
            url.setViewRefreshMode(ViewRefreshMode.ON_STOP);
            url.setViewRefreshTime(1);
        }
    }

    /**
     * @return the aggregated bounds for all the requested layers, taking into account whether the
     *         whole layer or filtered bounds is used for each layer
     */
    private ReferencedEnvelope computePerLayerQueryBounds(final WMSMapContent context,
            final List<ReferencedEnvelope> target, final LookAt lookAt) {

        // no need to compute queried bounds if request explicitly specified the view area
        final boolean computeQueryBounds = lookAt == null;

        ReferencedEnvelope aggregatedBounds;
        try {
            boolean longitudeFirst = true;
            aggregatedBounds = new ReferencedEnvelope(CRS.decode("EPSG:4326", longitudeFirst));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        aggregatedBounds.setToNull();

        final List<Layer> mapLayers = context.layers();
        final List<MapLayerInfo> layerInfos = context.getRequest().getLayers();
        for (int i = 0; i < mapLayers.size(); i++) {
            final Layer Layer = mapLayers.get(i);
            final MapLayerInfo layerInfo = layerInfos.get(i);

            ReferencedEnvelope layerLatLongBbox;
            layerLatLongBbox = computeLayerBounds(Layer, layerInfo, computeQueryBounds);
            try {
                layerLatLongBbox = layerLatLongBbox.transform(
                        aggregatedBounds.getCoordinateReferenceSystem(), true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            target.add(layerLatLongBbox);
            aggregatedBounds.expandToInclude(layerLatLongBbox);
        }
        return aggregatedBounds;
    }

    @SuppressWarnings("rawtypes")
    private ReferencedEnvelope computeLayerBounds(Layer layer, MapLayerInfo layerInfo,
            boolean computeQueryBounds) {

        final Query layerQuery = layer.getQuery();
        // make sure if layer is going to be filtered, the resulting bounds are obtained instead of
        // the whole bounds
        final Filter filter = layerQuery.getFilter();
        if (layerQuery.getFilter() == null || Filter.INCLUDE.equals(filter)) {
            computeQueryBounds = false;
        }
        if (!computeQueryBounds && !layerQuery.isMaxFeaturesUnlimited()) {
            computeQueryBounds = true;
        }

        ReferencedEnvelope layerLatLongBbox = null;
        if (computeQueryBounds) {
            FeatureSource featureSource = layer.getFeatureSource();
            try {
                CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326");
                FeatureCollection features = featureSource.getFeatures(layerQuery);
                layerLatLongBbox = features.getBounds();
                layerLatLongBbox = layerLatLongBbox.transform(targetCRS, true);
            } catch (Exception e) {
                LOGGER.info("Error computing bounds for " + featureSource.getName() + " with "
                        + layerQuery);
            }

        }
        if (layerLatLongBbox == null) {
            try {
                layerLatLongBbox = layerInfo.getLatLongBoundingBox();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return layerLatLongBbox;
    }

    public MapProducerCapabilities getCapabilities(String format) {
        return KMLMapOutputFormat.KML_CAPABILITIES;
    }

}
