package ca.ubc.cs.cpsc210.translink.ui;

import android.app.Activity;
import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import ca.ubc.cs.cpsc210.translink.BusesAreUs;
import ca.ubc.cs.cpsc210.translink.R;
import ca.ubc.cs.cpsc210.translink.model.*;
import ca.ubc.cs.cpsc210.translink.model.exception.StopException;
import ca.ubc.cs.cpsc210.translink.parsers.RouteMapParser;
import ca.ubc.cs.cpsc210.translink.parsers.StopParser;
import ca.ubc.cs.cpsc210.translink.parsers.exception.StopDataMissingException;
import ca.ubc.cs.cpsc210.translink.util.Geometry;
import ca.ubc.cs.cpsc210.translink.util.LatLon;
import ca.ubc.cs.cpsc210.translink.util.SphericalGeometry;
import org.json.JSONException;
import org.osmdroid.DefaultResourceProxyImpl;
import org.osmdroid.ResourceProxy;
import org.osmdroid.api.IMapController;
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer;
import org.osmdroid.bonuspack.overlays.MapEventsOverlay;
import org.osmdroid.bonuspack.overlays.MapEventsReceiver;
import org.osmdroid.bonuspack.overlays.Marker;
import org.osmdroid.bonuspack.overlays.Polyline;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.OverlayManager;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.IOException;
import java.util.*;

/**
 * Represents a fragment used to display the map to the user
 */
public class MapDisplayFragment extends Fragment implements MapEventsReceiver, IMyLocationConsumer {
    private static final String MDF_TAG = "MDF_TAG";
    /** minimum change in distance to trigger update of user location */
    private static final float MIN_UPDATE_DISTANCE = 50.0f;
    /** zoom level for map */
    private int zoomLevel = 15;
    /** centre of map */
    private GeoPoint mapCentre = new GeoPoint(49.2610, -123.2490);
    /** the map view */
    private MapView mapView;
    /** overlays used to plot bus routes */
    private List<Polyline> busRouteOverlays;
    /** overlay used to display location of user */
    private MyLocationNewOverlay locOverlay;
    /** overlay used to show stop markers */
    private RadiusMarkerClusterer stopClusterer;
    /** window displayed when user selects a stop */
    private StopInfoWindow stopInfoWindow;
    /** overlay that listens for user initiated events on map */
    private MapEventsOverlay eventsOverlay;
    /** overlay used to display bus route legend text on a layer above the map */
    private BusRouteLegendOverlay busRouteLegendOverlay;
    /** location provider used to respond to changes in user location */
    private GpsMyLocationProvider locnProvider;
    /** marker for stop that is nearest to user (null if no such stop) */
    private Marker nearestStopMarker;
    /** location listener used to respond to changes in user location */
    private LocationListener locationListener;
    /** last known user location (null if not available) */
    private Location lastKnownFromInstanceState;
    /** corners of visible rectangle on map */
    private LatLon northWest, southEast;
    /** maps each stop to its marker on the map */
    private Map<Stop, Marker> stopMarkerMap = new HashMap<>();


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(MDF_TAG, "onCreate");
        eventsOverlay = new MapEventsOverlay(getActivity(), this);
        locnProvider = new GpsMyLocationProvider(getActivity());
        locnProvider.setLocationUpdateMinDistance(MIN_UPDATE_DISTANCE);
        nearestStopMarker = null;
        busRouteOverlays = new ArrayList<>();
        newStopClusterer();
        parseStops();
        parseRouteMapText();
    }

    /**
     * Return a scaling factor for resources that should stay "about the same size" on the screen
     *
     * @return a factor to multiply fonts and widths of things to keep them visible on screen of varying resolution
     */
    private float dpiFactor() {
        float x = getResources().getDisplayMetrics().density;
        return x > 2.0f ? x / 2.0f : 1.0f;
    }

    /**
     * Create a new stop cluster object used to group stops that are close by to reduce screen clutter
     */
    private void newStopClusterer() {
        stopClusterer = new RadiusMarkerClusterer(getActivity());
        stopClusterer.getTextPaint().setTextSize(20.0F * dpiFactor());
        int zoom = mapView == null ? 16 : mapView.getZoomLevel();
        int radius = (1000/16);
        if (zoom!=0) {
            radius = 1000 / zoom;
        }
        stopClusterer.setRadius(radius);
        Drawable clusterIconD = getResources().getDrawable(R.drawable.stop_cluster);
        Bitmap clusterIcon = ((BitmapDrawable) clusterIconD).getBitmap();
        stopClusterer.setIcon(clusterIcon);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        locationListener = (LocationListener) activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final int TILE_SIZE = 256;
        Log.i(MDF_TAG, "onCreateView");

        if (savedInstanceState != null) {
            Log.i(MDF_TAG, "restoring from instance state");
            mapCentre = new GeoPoint(savedInstanceState.getDouble(getString(R.string.lat_key)),
                    savedInstanceState.getDouble(getString(R.string.lon_key)));
            zoomLevel = savedInstanceState.getInt(getString(R.string.zoom_key));
            lastKnownFromInstanceState = savedInstanceState.getParcelable(getString(R.string.locn_key));
        } else {
            Log.i(MDF_TAG, "savedInstanceState is null - new fragment created");
        }

        if (mapView == null) {
            System.out.println("Making new mapView");
            clearMarkers();
            mapView = new MapView(getActivity(), TILE_SIZE);
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setClickable(true);
            mapView.setBuiltInZoomControls(true);
            mapView.setMultiTouchControls(true);
            mapView.setTilesScaledToDpi(true);
            mapView.setMapListener(new DelayedMapListener(new BusRouteListener(), 100));

            GpsMyLocationProvider mapLocnProvider = new GpsMyLocationProvider(getActivity());
            mapLocnProvider.setLocationUpdateMinDistance(MIN_UPDATE_DISTANCE);
            locOverlay = new MyLocationNewOverlay(getActivity(), mapLocnProvider, mapView);
            stopInfoWindow = new StopInfoWindow((StopSelectionListener) getActivity(), mapView);
            createBusRouteLegendOverlay();

            centerAt(mapCentre);
        }

        return mapView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.i(MDF_TAG, "onSaveInstanceState");

        outState.putDouble(getString(R.string.lat_key), mapView.getMapCenter().getLatitude());
        outState.putDouble(getString(R.string.lon_key), mapView.getMapCenter().getLongitude());
        outState.putInt(getString(R.string.zoom_key), mapView.getZoomLevel());

        // if location has been updated, use it; otherwise use last known locn restored from instance state
        Location lastKnown = locnProvider.getLastKnownLocation();
        if (lastKnown != null) {
            outState.putParcelable(getString(R.string.locn_key), locnProvider.getLastKnownLocation());
        } else {
            outState.putParcelable(getString(R.string.locn_key), lastKnownFromInstanceState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(MDF_TAG, "onResume");
        locnProvider.startLocationProvider(this);
        locOverlay.enableMyLocation();
        mapView.setBuiltInZoomControls(true);

        Location lastKnownLocation = locnProvider.getLastKnownLocation();
        if (lastKnownLocation != null) {
            Log.i(MDF_TAG, "Restored from last known location");
            handleLocationChange(lastKnownLocation);
        } else if (lastKnownFromInstanceState != null) {
            Log.i(MDF_TAG, "Restored from instance state");
            handleLocationChange(lastKnownFromInstanceState);
            // force location overlay to redraw location icon
            locOverlay.onLocationChanged(lastKnownFromInstanceState, null);
        } else {
            Log.i(MDF_TAG, "Location cannot be recovered");
        }
        updateOverlays();
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(MDF_TAG, "onPause");
        locOverlay.disableMyLocation();
        locnProvider.stopLocationProvider();
        mapView.setBuiltInZoomControls(false);
    }

    /**
     * Clear overlays and add route, stop, location and events overlays
     */
    private void updateOverlays() {
        OverlayManager om = mapView.getOverlayManager();
        om.clear();
        om.addAll(busRouteOverlays);
        om.add(stopClusterer);
        om.add(locOverlay);
        om.add(busRouteLegendOverlay);
        om.add(0, eventsOverlay);

        mapView.invalidate();
    }

    /**
     * Create text overlay to display bus route colours
     */
    private void createBusRouteLegendOverlay() {
        ResourceProxy rp = new DefaultResourceProxyImpl(getActivity());
        busRouteLegendOverlay = new BusRouteLegendOverlay(rp, dpiFactor());
    }

    /**
     * Parse stop data from the file and add all stops to stop manager.
     */
    private void parseStops() {
        try {
            new StopParser("stops").parse();
        } catch (IOException | StopDataMissingException | JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parse route map data from the file and add all routes and patterns to the route manager.
     */
    private void parseRouteMapText() {
        new RouteMapParser("allroutemapstxt").parse();
    }

    /**
     * Plot each visible segment of each route pattern of each route going through the selected stop.
     * <p>
     */
    public void plotRoutes() {
        updateVisibleArea();
        busRouteOverlays.clear();
        Stop s = StopManager.getInstance().getSelected();
        busRouteLegendOverlay.clear();
        if (s!=null){
            Set<Route> lor =s.getRoutes();
            for (Route r:lor
                 ) {
                busRouteLegendOverlay.add(r.getNumber());
                List<RoutePattern> lop =r.getPatterns();
                Integer colour =busRouteLegendOverlay.getColor(r.getNumber());
                List<Polyline> segments = getSegments(lop,colour);
                for (Polyline line:segments
                     ) {
                    busRouteOverlays.add(line);
                }
            }
            updateOverlays();
        }
    }

    /**
     * Helper method for plotRoutes; gets a list of RoutePatterns and a colour.
     * If a segment is in the screen, creates a line between two points in the Path with the colour of
     * the particular RoutePattern.
     *
     * @return a list containing each segment of a route pattern
     */
    private List<Polyline> getSegments(List<RoutePattern> rps,Integer colour){
        List<Polyline> lop = new ArrayList<>();
        for (RoutePattern rp:rps
                ) {
            List<LatLon> path=rp.getPath();
            if (path.size()>=2){
                for (Integer i = 0; i < path.size()-1; i += 1
                     ) {
                    if (Geometry.rectangleIntersectsLine(northWest,southEast,path.get(i),path.get(i+1))) {
                        Polyline p = new Polyline(BusesAreUs.activity.getApplicationContext());
                        p.setWidth(getLineWidth(zoomLevel));
                        p.setColor(colour);
                        List<GeoPoint> log = new ArrayList<>();
                        log.add(new GeoPoint(path.get(i).getLatitude(), path.get(i).getLongitude()));
                        log.add(new GeoPoint(path.get(i + 1).getLatitude(), path.get(i + 1).getLongitude()));
                        p.setPoints(log);
                        lop.add(p);
                    }

                }
            }
        }
        return lop;
    }

    /**
     * Update the fields northWest and southEast to correspond to the corners of the visible area of the map.
     * These fields can then be used to determine what stops and bus route pattern segments are visible.
     */
    private void updateVisibleArea() {
        GeoPoint northwest = (GeoPoint) mapView.getProjection().fromPixels(0, 0);
        GeoPoint southeast = (GeoPoint) mapView.getProjection().fromPixels(mapView.getWidth(), mapView.getHeight());
        northWest = new LatLon(northwest.getLatitude(), northwest.getLongitude());
        southEast = new LatLon(southeast.getLatitude(), southeast.getLongitude());
    }

    /**
     * Creates a line break in a String
     */
    public static String newLine = System.getProperty("line.separator");

    /**
     * Creates a String of Routes separated by a line break
     * @return a String of all the Routes in a particular Stop
     */
    public String getArrivalString(Stop s){
        Set<Route> lor = s.getRoutes();
        String str = new String();
        List<String> los = new ArrayList<>();
        for (Route r:lor
             ) {
            los.add(newLine+(r.getNumber()));
        }
        for (String string:los
             ) {
            str =str.concat(string);
        }
        return str;
    }

    /**
     * Mark all visible stops in stop manager onto map.
     * <p>
     *
     * Looks at all stops in StopManager, adds them to a list if they're in the screen, then determines which colour
     * to make the stop marker depending on if the marker has been set as the
     * nearestStopMarker in updateMarkerOfNearest.
     *
     */
    private void markStops() {
        Drawable stopIconDrawable = getResources().getDrawable(R.drawable.stop_icon);
        Drawable closestStopIconDrawable = getResources().getDrawable(R.drawable.closest_stop_icon);
        updateVisibleArea();
        newStopClusterer();
        List<Stop> los = new ArrayList<>();
        for (Stop s: StopManager.getInstance()
                ) {
            if (Geometry.rectangleContainsPoint(northWest,southEast,s.getLocn())){
                los.add(s);
            }
        }
        for (Stop s: los
                ) {
            if (getMarker(s)!=null){
                if (getMarker(s).equals(nearestStopMarker)){
                    getMarker(s).setIcon(closestStopIconDrawable);
                    getMarker(s).setPosition(new GeoPoint(s.getLocn().getLatitude(),s.getLocn().getLongitude()));
                    getMarker(s).setTitle(s.getNumber() +newLine+ s.getName()+newLine+getArrivalString(s));
                    getMarker(s).setInfoWindow(stopInfoWindow);
                    stopClusterer.add(getMarker(s));
               }else{
                getMarker(s).setIcon(stopIconDrawable);
                getMarker(s).setPosition(new GeoPoint(s.getLocn().getLatitude(),s.getLocn().getLongitude()));
                getMarker(s).setTitle(s.getNumber() +newLine+ s.getName()+newLine+getArrivalString(s));
                getMarker(s).setInfoWindow(stopInfoWindow);
                stopClusterer.add(getMarker(s));}
            }else{
            Marker m = new Marker(mapView);
                setMarker(s, m);
                m.setRelatedObject(s);
                m.setPosition(new GeoPoint(s.getLocn().getLatitude(), s.getLocn().getLongitude()));
                m.setTitle(s.getNumber() +newLine+ s.getName() +newLine+getArrivalString(s));
                m.setIcon(stopIconDrawable);
                m.setInfoWindow(stopInfoWindow);
                stopClusterer.add(m);}
            updateOverlays();
        }
    }

    /**
     * Update marker of nearest stop (called when user's location has changed).  If nearest is null,
     * no stop is marked as the nearest stop.
     *
     * Also sets nearestStopMarker.
     *
     * @param nearest stop nearest to user's location (null if no stop within StopManager.RADIUS metres)
     *                <p>
     */
    private void updateMarkerOfNearest(Stop nearest) {
        Drawable stopIconDrawable = getResources().getDrawable(R.drawable.stop_icon);
        Drawable closestStopIconDrawable = getResources().getDrawable(R.drawable.closest_stop_icon);
        Marker m =null;
        if (nearest!=null) {
            markStops();
            if (getMarker(nearest)!=null) {
                m = getMarker(nearest);
                m.setRelatedObject(nearest);
                m.setIcon(closestStopIconDrawable);
                nearestStopMarker=m;
            }
        }
        markStops();
    }

    /**
     * Centers map at given GeoPoint
     *
     * @param center
     */
    private void centerAt(final GeoPoint center) {
        final IMapController mapController = mapView.getController();

        mapView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @SuppressWarnings("deprecation")
            @Override
            public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                    mapView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                else
                    mapView.getViewTreeObserver().removeGlobalOnLayoutListener(this);

                mapController.setZoom(zoomLevel);
                mapController.setCenter(center);
            }
        });
        Log.i(MDF_TAG, "Centered location : " + center);
    }

    /**
     * Find nearest stop to user, update nearest stop text view and update markers on user location change.
     * Calls updateMarkerOfNearest to do part of this work.
     * Also, calls locationListener.onLocationChanged with the closest stop and the current location as a LatLon.
     *
     * @param location the location of the user
     *                 <p>
     */
    private void handleLocationChange(Location location) {
        markStops();
        Stop tn = StopManager.getInstance().findNearestTo
                (new LatLon(location.getLatitude(),location.getLongitude()));
        Stop nearest = null;
        if (tn!=null) {
            if (StopManager.RADIUS < SphericalGeometry.distanceBetween(tn.getLocn(),
                    new LatLon(location.getLatitude(), location.getLongitude()))) {
                nearest = null;
            } else nearest = tn;
        } else nearestStopMarker = null;
        updateMarkerOfNearest(nearest);
        locationListener.onLocationChanged(nearest,new LatLon(location.getLatitude(),location.getLongitude()));
    }


    /**
     * Get width of line used to plot bus route based on zoom level
     *
     * @param zoomLevel the zoom level of the map
     * @return width of line used to plot bus route
     */
    private float getLineWidth(int zoomLevel) {
        if (zoomLevel > 14)
            return 7.0f * dpiFactor();
        else if (zoomLevel > 10)
            return 5.0f * dpiFactor();
        else
            return 2.0f * dpiFactor();
    }

    /**
     * Close info windows when user taps map.
     */
    @Override
    public boolean singleTapConfirmedHelper(GeoPoint geoPoint) {
        StopInfoWindow.closeAllInfoWindowsOn(mapView);
        try {
            StopManager.getInstance().setSelected();
        } catch (StopException e) {
            e.printStackTrace();
        }
        plotRoutes();
        markStops();
        return false;
    }

    @Override
    public boolean longPressHelper(GeoPoint geoPoint) {
        return false;
    }

    /**
     * Called when user's location has changed - handle location change and repaint map
     *
     * @param location            user's location
     * @param iMyLocationProvider location provider
     */
    @Override
    public void onLocationChanged(Location location, IMyLocationProvider iMyLocationProvider) {
        Log.i(MDF_TAG, "onLocationChanged");

        handleLocationChange(location);
        mapView.invalidate();
    }

    /**
     * Custom listener for zoom events.  Changes width of line used to plot
     * bus routes based on zoom level.
     */
    private class BusRouteListener implements MapListener {

        @Override
        public boolean onScroll(ScrollEvent scrollEvent) {
            plotRoutes();
            markStops();

            mapView.invalidate();
            return false;
        }

        @Override
        public boolean onZoom(ZoomEvent zoomEvent) {
            zoomLevel = mapView.getZoomLevel();
            busRouteOverlays.clear();
            plotRoutes();
            markStops();
            return false;
        }
    }

    /**
     * Manage mapping from stops to markers using a map from stops to markers.
     * The mapping in the other direction is done using the Marker.setRelatedObject() and
     * Marker.getRelatedObject() methods.
     */
    private Marker getMarker(Stop stop) {
        return stopMarkerMap.get(stop);
    }

    private void setMarker(Stop stop, Marker marker) {
        stopMarkerMap.put(stop, marker);
    }

    private void clearMarker(Stop stop) {
        stopMarkerMap.remove(stop);
    }

    private void clearMarkers() {
        stopMarkerMap.clear();
    }
}
