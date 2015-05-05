package com.affinitybridge.cordova.mapbox;

import android.graphics.PointF;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.DragEvent;
import android.view.View;

import com.cocoahero.android.geojson.Feature;
import com.cocoahero.android.geojson.FeatureCollection;
import com.cocoahero.android.geojson.GeoJSONObject;
import com.cocoahero.android.geojson.Geometry;
import com.cocoahero.android.geojson.LineString;
import com.cocoahero.android.geojson.Point;
import com.cocoahero.android.geojson.Polygon;
import com.cocoahero.android.geojson.Position;
import com.cocoahero.android.geojson.Ring;
import com.mapbox.mapboxsdk.geometry.BoundingBox;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.overlay.Icon;
import com.mapbox.mapboxsdk.overlay.Marker;
import com.mapbox.mapboxsdk.util.GeoUtils;
import com.mapbox.mapboxsdk.views.MapView;
import com.mapbox.mapboxsdk.views.util.Projection;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineSegment;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Created by tnightingale on 15-04-21.
 */
class Builder {

    protected MapView mapView;

    protected BuilderInterface activeBuilder;

    protected int selected = -1;

    protected Vertex lastAdded;

    protected ArrayList<Vertex> vertices;

    protected ArrayList<Marker> markers;

    protected DraggableItemizedIconOverlay markerOverlay;

    Drawable vertexImage;

    Drawable vertexMiddleImage;

    Drawable vertexSelectedImage;

    public Builder(MapView mv) {
        this.mapView = mv;
        this.vertices = new ArrayList<Vertex>();
        this.markers = new ArrayList<Marker>();

        this.markerOverlay = new DraggableItemizedIconOverlay(this.mapView.getContext(), new ArrayList<Marker>(), new DraggableItemizedIconOverlay.OnItemDraggableGestureListener<Marker>() {
            public boolean onItemSingleTapUp(final int iconOverlayMarkerIndex, final Marker item) {
                int index = markers.indexOf(item);
                Log.d("Builder", String.format("onSingleTapUp() index: %d, selected: %d.", markers.indexOf(item), selected));

                if (index == selected) {
                    return false;
                }

                Vertex vertex = vertices.get(index);

                select(index, vertex);
                return true;
            }

            public boolean onItemLongPress(final int iconOverlayMarkerIndex, final Marker item) {
                int index = markers.indexOf(item);
                Log.d("Builder", String.format("onItemLongPress() index: %d, selected: %d.", index, selected));

                if (vertices.get(index).isGhost()) {
                    // If middle marker; ignore.
                    return false;
                }

                // If real marker; remove it.
                removePoint(index);
                return true;
            }

            public boolean onItemDown(final int iconOverlayMarkerIndex, final Marker item) {
                Log.d("Builder", String.format("onItemDown() index: %d, selected: %d.", markers.indexOf(item), selected));

                if (markers.indexOf(item) != selected) {
                    return false;
                }

                mapView.startDrag(null, new MarkerShadowBuilder(mapView, item), null, 0);
                return true;
            }
        });

        mapView.setOnDragListener(new MarkerDragEventListener());
        this.mapView.addItemizedOverlay(this.markerOverlay);
    }


    public void setVertexImage(Drawable img) {
        this.vertexImage = img;
    }

    public void setVertexSelectedImage(Drawable img) {
        this.vertexSelectedImage = img;
    }

    public void setVertexMiddleImage(Drawable img) {
        this.vertexMiddleImage = img;
    }

    protected void select(int index) {
        Vertex vertex = this.vertices.get(index);
        this.select(index, vertex);
    }

    protected void select(int index, Vertex vertex) {
        this.deselect();
        this.selected = index;

        BuilderInterface builder = vertex.getOwner();

        Log.d("Builder", String.format("select() vertex.isGhost() ? %b.", vertex.isGhost()));
        if (vertex.isGhost() && builder.add(vertex.getPoint())) {
            // Promote middle vertex to real vertex.
            ArrayList<LatLng> latLngs = builder.getLatLngs();
            int insertPos = latLngs.indexOf(vertex.getNext().getPoint());
            latLngs.add(insertPos, vertex.getPoint());
            vertex.setGhost(false);

            updatePrevNext(vertex.getPrev(), vertex);
            updatePrevNext(vertex, vertex.getNext());

            createMiddleMarker(vertex.getPrev(), vertex);
            createMiddleMarker(vertex, vertex.getNext());

            builder.reset();
        }

        if (this.vertexSelectedImage != null) {
            vertex.getMarker().setHotspot(Marker.HotspotPlace.CENTER);
            vertex.getMarker().setMarker(this.vertexSelectedImage);
        }
        else {
            vertex.getMarker().setIcon(new Icon(this.mapView.getContext(), Icon.Size.SMALL, "", "FF0000"));
        }

        Log.d("Builder", String.format("select() this.selected: %d", this.selected));
    }

    protected void deselect() {
        if (this.selected < 0) {
            return;
        }
        Vertex vertex = this.vertices.get(this.selected);

        if (this.vertexImage != null) {
            vertex.getMarker().setHotspot(Marker.HotspotPlace.CENTER);
            vertex.getMarker().setMarker(this.vertexImage);
        }
        else {
            vertex.getMarker().setIcon(new Icon(this.mapView.getContext(), Icon.Size.SMALL, "", "0000FF"));
        }

        this.selected = -1;
    }

    protected void initMarkers(BuilderInterface builder) {
        ArrayList<LatLng> latLngs = builder.getLatLngs();
        ArrayList<Vertex> newVertices = new ArrayList<Vertex>();

        // Initialize markers for all vertices.
        for (LatLng latLng : latLngs) {
            Log.d("Builder", String.format("LatLng: (%f, %f).", latLng.getLatitude(), latLng.getLongitude()));
            if (builder.add(latLng)) {
                Marker marker = this.createMarker(latLng, this.vertexImage);
                Vertex vertex = new Vertex(builder, marker);
                newVertices.add(vertex);
            }
        }

        // Add all new vertices to main collection.
        this.vertices.addAll(newVertices);

        // Initialize middle markers.
        Vertex left, right;
        int length = latLngs.size();
        for (int i = 0, j = length - 1; i < length; j = i++) {
            left = newVertices.get(j);
            right = newVertices.get(i);
            this.createMiddleMarker(left, right);
            this.updatePrevNext(left, right);
        }
    }

    protected void createMiddleMarker(Vertex left, Vertex right) {
        if (left == null || right == null) {
            return;
        }

        LatLng middle = this.getMiddleLatLng(left.getPoint(), right.getPoint());
        Marker marker = this.createMarker(middle, this.vertexMiddleImage);

        Vertex vertex = new Vertex(left.getOwner(), marker);
        vertex.setGhost(true);

        this.vertices.add(vertex);

        left.setMiddleRight(vertex);
        right.setMiddleLeft(vertex);
    }

    protected void updatePrevNext(Vertex left, Vertex right) {
        if (left != null) {
            left.setNext(right);
        }
        if (right != null) {
            right.setPrev(left);
        }
    }

    protected LatLng getMiddleLatLng(LatLng left, LatLng right) {
        LineSegment seg = new LineSegment(left.getLongitude(), left.getLatitude(), right.getLongitude(), right.getLatitude());
        Coordinate mid = seg.midPoint();
        return new LatLng(mid.y, mid.x);
    }

    final public void addPoint(BuilderInterface builder) {
        LatLng position = mapView.getCenter();

        if (builder.add(position)) {
            Marker marker = this.createMarker(position, this.vertexImage);
            Vertex vertex = new Vertex(builder, marker);
            this.vertices.add(vertex);

            ArrayList<LatLng> latLngs = vertex.getOwner().getLatLngs();
            latLngs.add(position);

            if (latLngs.size() > 1 && this.lastAdded != null) {
                this.createMiddleMarker(this.lastAdded, vertex);
                this.updatePrevNext(this.lastAdded, vertex);
            }

            this.lastAdded = vertex;
        }
    }

    final public Marker createMarker(LatLng latLng, Drawable image) {
        Marker marker = new Marker("", "", latLng);

        if (image != null) {
            marker.setHotspot(Marker.HotspotPlace.CENTER);
            marker.setAnchor(new PointF(0.5f, 0.5f));
            marker.setMarker(image);
        }
        else {
            marker.setIcon(new Icon(this.mapView.getContext(), Icon.Size.SMALL, "", "0000FF"));
        }

        this.markers.add(marker);

        this.markerOverlay.addItem(marker);
        marker.addTo(mapView);

        this.mapView.invalidate();

        return marker;
    }

    final public void removePoint() {
        int index = this.selected >= 0 ? this.selected : - 1;

        this.removePoint(index);
    }

    final public void removePoint(int index) {
        if (index < 0) {
            return;
        }

        // Calling this.deselect() is necessary as removing the vertex and marker will shift
        // subsequent indices in this.vertices & this.markers.
        this.deselect();

        Vertex vertex = this.vertices.remove(index);
        ArrayList<LatLng> latLngs = vertex.getOwner().getLatLngs();
        Marker marker = this.markers.remove(index);

        updatePrevNext(vertex.getPrev(), vertex.getNext());
        createMiddleMarker(vertex.getPrev(), vertex.getNext());

        Vertex middleLeft = vertex.getMiddleLeft();
        Vertex middleRight = vertex.getMiddleRight();

        if (middleLeft != null) {
            this.markerOverlay.removeItem(middleLeft.getMarker());
        }
        if (middleRight != null) {
            this.markerOverlay.removeItem(middleRight.getMarker());
        }

        this.markerOverlay.removeItem(marker);
        latLngs.remove(latLngs.indexOf(marker.getPoint()));

        vertex.getOwner().remove(index);

        if (this.lastAdded == vertex) {
            this.lastAdded = vertex.getPrev();
        }

        marker.getDrawable().invalidateSelf();
        mapView.invalidate();
    }

    public JSONObject toJSON() {
        return new JSONObject();//this.activeBuilder.toJSON();
    }

    private class MarkerDragEventListener implements View.OnDragListener {
        protected Vertex activeVertex;
        protected int activeIndex;

        private boolean dragStart(View v, DragEvent event) {
            Vertex vertex = vertices.get(selected);

            this.activeVertex = vertex;
            this.activeIndex = vertex.getOwner().getLatLngs().indexOf(vertex.getPoint());

            markerOverlay.removeItem(vertex.getMarker());
            Log.d("Builder", String.format("dragStart() selected: %d, activeIndex: %d", selected, activeIndex));

            return true;
        }

        private boolean dragLocation(View v, DragEvent event) {
            Projection p = mapView.getProjection();
            LatLng latLng = (LatLng) p.fromPixels(event.getX(), event.getY());

            this.activeVertex.getOwner().getLatLngs().set(this.activeIndex, latLng);
            // Let implementing classes perform reset action.
            this.activeVertex.getOwner().reset();

            Vertex vertex = vertices.get(selected);
            Vertex prev = vertex.getPrev();
            Vertex next = vertex.getNext();

            if (prev != null) {
                vertex.getMiddleLeft().setPoint(getMiddleLatLng(prev.getPoint(), latLng));
            }

            if (next != null) {
                vertex.getMiddleRight().setPoint(getMiddleLatLng(latLng, next.getPoint()));
            }

            // Invalidating the view causes a redraw.
            v.invalidate();
            return true;
        }

        private boolean dragDrop(View view, DragEvent event) {
            Projection p = mapView.getProjection();
            Vertex v = vertices.get(selected);

            LatLng latLng = (LatLng) p.fromPixels(event.getX(), event.getY());
            v.getOwner().getLatLngs().set(this.activeIndex, latLng);

            v.setPoint(latLng);
            markerOverlay.addItem(v.getMarker());
            v.getMarker().addTo(mapView);

            return true;
        }

        public boolean onDrag(View v, DragEvent event) {
            final int action = event.getAction();

            switch (action) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return dragStart(v, event);
                case DragEvent.ACTION_DRAG_ENTERED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    return this.dragLocation(v, event);
                case DragEvent.ACTION_DRAG_EXITED:
                    return true;
                case DragEvent.ACTION_DROP:
                    return this.dragDrop(v, event);
                case DragEvent.ACTION_DRAG_ENDED:
                    return true;
                default:
                    Log.e("MarkerDragEventListener","Unknown action type received by OnDragListener.");
                    break;
            }
            return false;
        }
    }

    public static interface BuilderInterface {

        public void reset();

        public boolean add(LatLng position);

        public void remove(int index);

        public JSONObject toJSON();

        public ArrayList<LatLng> getLatLngs();

    }
}