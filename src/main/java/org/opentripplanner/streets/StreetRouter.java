package org.opentripplanner.streets;

import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.pqueue.BinHeap;
import org.opentripplanner.transit.TransitLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 *
 */
public class StreetRouter {

    private static final Logger LOG = LoggerFactory.getLogger(StreetRouter.class);

    private static final boolean DEBUG_OUTPUT = false;

    public static final int ALL_VERTICES = -1;

    private StreetLayer streetLayer;

    private TransitLayer transitLayer;

    public int distanceLimitMeters = 2_000;

    TIntObjectMap<State> bestStates = new TIntObjectHashMap<>();

    BinHeap<State> queue = new BinHeap<>();

    boolean goalDirection = false;

    double targetLat, targetLon; // for goal direction heuristic

    public TIntSet transitStopVerticesHit = new TIntHashSet();

    /**
     * @return a map from transit stop indexes to their distances from the origin
     */
    public TIntIntMap timesToReachedStops () {
        TIntIntMap result = new TIntIntHashMap();
        // Convert stop vertex indexes in street layer to transit layer stop indexes.
        transitStopVerticesHit.forEach(vertexIndex -> {
            int weight = bestStates.get(vertexIndex).weight;
            int stopIndex = transitLayer.stopForStreetVertex.get(vertexIndex);
            result.put(stopIndex, weight);
            return true; // continue iteration
        });
        return result;
    }

    public StreetRouter (StreetLayer streetLayer, TransitLayer transitLayer) {
        this.streetLayer = streetLayer;
        this.transitLayer = transitLayer;
    }

    public void route (int fromVertex, int toVertex) {
        long startTime = System.currentTimeMillis();
        bestStates.clear();
        queue.reset();
        transitStopVerticesHit.clear();
        State startState = new State(fromVertex, -1, null);
        bestStates.put(fromVertex, startState);
        queue.insert(startState, 0);

        PrintStream printStream; // for debug output
        if (DEBUG_OUTPUT) {
            File debugFile = new File(String.format("%d-%d.csv", fromVertex, toVertex));
            OutputStream outputStream;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(debugFile));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            printStream = new PrintStream(outputStream);
            printStream.println("lat,lon,weight");
        }

        if (toVertex > 0) {
            goalDirection = true;
            VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(toVertex);
            targetLat = vertex.getLat();
            targetLon = vertex.getLon();
        }
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        while (!queue.empty()) {
            State s0 = queue.extract_min();
            if (bestStates.get(s0.vertex) != s0) {
                continue; // state was dominated after being enqueued
            }
            int v0 = s0.vertex;
            if (goalDirection && v0 == toVertex) {
                LOG.debug("Found destination vertex. Tree size is {}.", bestStates.size());
                break;
            }
            if (DEBUG_OUTPUT) {
                VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(v0);
                printStream.printf("%f,%f,%d\n", vertex.getLat(), vertex.getLon(), s0.weight);
            }
            TIntList edgeList = streetLayer.outgoingEdges.get(v0);
            edgeList.forEach(edgeIndex -> {
                edge.seek(edgeIndex);
                State s1 = edge.traverse(s0);
                if (!goalDirection && s1.weight > distanceLimitMeters) {
                    return true; // iteration should continue
                }
                State existingBest = bestStates.get(s1.vertex);
                if (existingBest == null || existingBest.weight > s1.weight) {
                    bestStates.put(s1.vertex, s1);
                    if (edge.getFlag(EdgeStore.Flag.TRANSIT_LINK) && edge.isForward()) {
                        transitStopVerticesHit.add(edge.getToVertex());
                        // Once we go into a transit stop, we don't follow any edges out of it.
                        return true; // iteration should continue
                    }
                }
                int remainingWeight = goalDirection ? heuristic(s1) : 0;
                queue.insert(s1, s1.weight + remainingWeight);
                return true; // iteration should continue
            });
        }
        if (DEBUG_OUTPUT) {
            printStream.close();
        }
        //LOG.info("Routing produced {} states.", bestStates.size());
    }

    /**
     * Estimate remaining weight to destination. Must be an underestimate.
     */
    private int heuristic (State s) {
        VertexStore.Vertex vertex = streetLayer.vertexStore.getCursor(s.vertex);
        double lat = vertex.getLat();
        double lon = vertex.getLon();
        return (int)SphericalDistanceLibrary.fastDistance(lat, lon, targetLat, targetLon);
    }

    public static class State implements Cloneable {
        public int vertex;
        public int weight;
        public int backEdge;
        public State backState; // previous state in the path chain
        public State nextState; // for multiple states at the same location
        public State (int atVertex, int viaEdge, State backState) {
            this.vertex = atVertex;
            this.backEdge = viaEdge;
            this.backState = backState;
        }
    }

}