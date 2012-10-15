/*
 * Copyright 2012 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock;

import org.oscim.core.MapPosition;
import org.oscim.core.Tile;
import org.oscim.generator.JobTile;
import org.oscim.renderer.layer.TextItem;
import org.oscim.renderer.layer.VertexPool;
import org.oscim.utils.GlConfigChooser;
import org.oscim.view.MapView;
import org.oscim.view.MapViewPosition;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.FloatMath;
import android.util.Log;

// FIXME to many 'Renderer', this one needs a better name.. TileLoader?
public class MapRenderer extends GLSurfaceView {
	private final static String TAG = "MapRenderer";
	private GLRenderer mRenderer;

	private static final int MAX_TILES_IN_QUEUE = 40;
	private static final int CACHE_THRESHOLD = 10;

	private static MapView mMapView;

	private static final MapPosition mMapPosition = new MapPosition();
	private final MapViewPosition mMapViewPosition;

	// new jobs for the MapWorkers
	private static ArrayList<JobTile> mJobList;

	// all tiles currently referenced
	private static ArrayList<MapTile> mTiles;

	// tiles that have new data to upload, see passTile()
	private static ArrayList<MapTile> mTilesLoaded;

	private static ArrayList<Overlay> mOverlays;

	// TODO current boundary tiles, values used to check if position has
	// changed for updating current tile list

	private static boolean mInitial;

	// private static MapPosition mCurPosition, mDrawPosition;
	private static int mWidth = 0, mHeight = 0;

	// maps zoom-level to available zoom-levels in MapDatabase
	// e.g. 16->16, 15->16, 14->13, 13->13, 12->13,....
	// private static int[] mZoomLevels;

	private static float[] mTileCoords = new float[8];

	// private static int[] mBoundaryTiles = new int[8];

	// used for passing tiles to be rendered from TileLoader(Main-Thread) to
	// GLThread
	static final class TilesData {
		int cnt = 0;
		int serial;
		MapTile[] tiles;

		TilesData() {
		}

		TilesData(int numTiles) {
			tiles = new MapTile[numTiles];
		}
	}

	static int mUpdateCnt;
	static ReentrantLock tilelock = new ReentrantLock();
	static TilesData mCurrentTiles;
	/* package */static TilesData mNewTiles;

	static int tileCounter;

	private static ScanBox mScanBox = new ScanBox() {

		@Override
		void setVisible(int y, int x1, int x2) {
			MapTile[] tiles = mNewTiles.tiles;
			int cnt = mNewTiles.cnt;
			int max = tiles.length;
			int xmax = 1 << mZoom;

			for (int x = x1; x < x2; x++) {
				MapTile tile = null;

				if (cnt == max) {
					Log.d(TAG, "reached maximum for currentTiles " + max);
					break;
				}

				// NOTE to myself: do not modify x!
				int xx = x;

				if (x < 0 || x >= xmax) {
					// flip-around date line
					if (x < 0)
						xx = xmax + x;
					else
						xx = x - xmax;

					if (xx < 0 || xx >= xmax)
						continue;
				}

				// check if tile is already added
				for (int i = 0; i < cnt; i++)
					if (tiles[i].tileX == xx && tiles[i].tileY == y) {
						tile = tiles[i];
						break;
					}

				if (tile == null) {
					tile = addTile(xx, y, mZoom, 0);
					tiles[cnt++] = tile;
				}
			}
			mNewTiles.cnt = cnt;
		}
	};

	// why not try a pattern every now and then?
	// but should do the same for GLRenderer.
	// findbugs found that volatile thingy, though this class
	// is created before any other thread starts
	private static volatile MapRenderer SINGLETON;

	public static MapRenderer create(Context context, MapView mapView) {
		if (SINGLETON != null)
			throw new IllegalStateException();

		return SINGLETON = new MapRenderer(context, mapView);
	}

	public void destroy() {
		SINGLETON = null;
		// mRenderer = null;
		// mTiles = null;
		// mTilesLoaded = null;
		// mJobList = null;
		// mOverlays = null;
		// ... free pools
	}

	private MapRenderer(Context context, MapView mapView) {
		super(context);

		mMapView = mapView;
		mMapViewPosition = mapView.getMapViewPosition();

		Log.d(TAG, "init GLSurfaceLayer");
		setEGLConfigChooser(new GlConfigChooser());
		setEGLContextClientVersion(2);

		// setDebugFlags(DEBUG_CHECK_GL_ERROR | DEBUG_LOG_GL_CALLS);
		mRenderer = new GLRenderer(mMapView);
		setRenderer(mRenderer);

		// if (!debugFrameTime)
		setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

		mJobList = new ArrayList<JobTile>();
		mTiles = new ArrayList<MapTile>();
		mTilesLoaded = new ArrayList<MapTile>(30);
		mOverlays = new ArrayList<Overlay>(5);

		VertexPool.init();
		QuadTree.init();

		mUpdateCnt = 0;
		mInitial = true;

		tileCounter = 0;
	}

	/**
	 * Update list of visible tiles and passes them to MapRenderer, when not
	 * available tiles are created and added to JobQueue (mapView.addJobs) for
	 * loading by TileGenerator class
	 * 
	 * @param clear
	 *            whether to clear and reload all tiles
	 */
	public synchronized void updateMap(final boolean clear) {
		boolean changedPos = false;

		if (mMapView == null)
			return;

		if (clear || mInitial) {
			// make sure onDrawFrame is not running
			GLRenderer.drawlock.lock();
			// remove all tiles references
			Log.d(TAG, "CLEAR " + mInitial);

			if (clear) {
				for (MapTile t : mTiles)
					clearTile(t);
			} else {
				VertexPool.init();
			}

			mTiles.clear();
			mTilesLoaded.clear();

			QuadTree.init();

			// TODO clear overlay items data
			mOverlays.clear();
			// mOverlays.add(new Overlay());

			// set up TileData arrays that are passed to gl-thread
			int num = mWidth;
			if (mWidth < mHeight)
				num = mHeight;

			int size = Tile.TILE_SIZE >> 1;

			int numTiles = (num * num) / (size * size) * 4;

			// mRenderer.clearTiles(numTiles);
			mNewTiles = new TilesData(numTiles);
			mCurrentTiles = new TilesData(numTiles);
			// MapInfo mapInfo = mMapView.getMapDatabase().getMapInfo();
			// if (mapInfo != null)
			// mZoomLevels = mapInfo.zoomLevel;
			GLRenderer.drawlock.unlock();

			// .. make sure mMapPosition will be updated
			mMapPosition.zoomLevel = -1;

			mInitial = false;
		}

		MapPosition mapPosition = mMapPosition;
		float[] coords = mTileCoords;
		changedPos = mMapViewPosition.getMapPosition(mapPosition, coords);

		if (!changedPos) {
			requestRender();
			return;
		}

		float s = Tile.TILE_SIZE;
		// load some additional tiles more than currently visible
		float scale = mapPosition.scale * 0.75f;
		float px = (float) mapPosition.x;
		float py = (float) mapPosition.y;

		int zdir = 0;

		for (int i = 0; i < 8; i += 2) {
			coords[i + 0] = (px + coords[i + 0] / scale) / s;
			coords[i + 1] = (py + coords[i + 1] / scale) / s;
		}

		// TODO all following should probably be done in an idler instead
		// to drain queued events. need to check how android handles things.

		boolean changed = updateVisibleList(mapPosition, zdir);

		if (!MapView.debugFrameTime)
			requestRender();

		if (changed) {
			int remove = mTiles.size() - GLRenderer.CACHE_TILES;

			if (remove > CACHE_THRESHOLD)
				limitCache(mapPosition, remove);

			limitLoadQueue();
		}
	}

	public static TilesData getActiveTiles(TilesData td) {

		if (td != null && td.serial == mUpdateCnt)
			return td;

		// dont flip new/currentTiles while copying
		MapRenderer.tilelock.lock();
		try {
			MapTile[] newTiles = mCurrentTiles.tiles;
			int cnt = mCurrentTiles.cnt;

			// lock tiles (and their proxies) to not be removed from cache
			for (int i = 0; i < cnt; i++)
				newTiles[i].lock();

			MapTile[] nextTiles;

			if (td == null)
				td = new TilesData(newTiles.length);

			nextTiles = td.tiles;

			// unlock previously active tiles
			for (int i = 0, n = td.cnt; i < n; i++)
				nextTiles[i].unlock();

			// copy newTiles to nextTiles
			System.arraycopy(newTiles, 0, nextTiles, 0, cnt);

			td.serial = mUpdateCnt;
			td.cnt = cnt;
		} finally {
			MapRenderer.tilelock.unlock();
		}

		return td;
	}

	// public void releaseTiles(TilesData tiles) {
	//
	// }

	/**
	 * set mNewTiles for the visible tiles and pass it to GLRenderer, add jobs
	 * for not yet loaded tiles
	 * 
	 * @param mapPosition
	 *            the current MapPosition
	 * @param zdir
	 *            zoom direction
	 * @return true if new tiles were loaded
	 */
	private static boolean updateVisibleList(MapPosition mapPosition, int zdir) {

		// TODO keep mJobList and JobQueue in sync, no need to clear
		mJobList.clear();

		// sets non processed tiles to isLoading = false
		// and clear job queue
		mMapView.addJobs(null);

		mNewTiles.cnt = 0;
		mScanBox.scan(mTileCoords, mapPosition.zoomLevel);

		MapTile[] newTiles = mNewTiles.tiles;
		MapTile[] curTiles = mCurrentTiles.tiles;

		boolean changed = false;

		for (int i = 0, n = mNewTiles.cnt; i < n && !changed; i++) {
			MapTile t = newTiles[i];
			boolean found = false;

			for (int j = 0, m = mCurrentTiles.cnt; j < m; j++) {
				if (t == curTiles[j]) {
					found = true;
					break;
				}
			}
			if (!found)
				changed = true;
		}

		if (changed) {
			MapRenderer.tilelock.lock();
			try {
				for (int i = 0, n = mNewTiles.cnt; i < n; i++)
					newTiles[i].lock();

				for (int i = 0, n = mCurrentTiles.cnt; i < n; i++)
					curTiles[i].unlock();

				TilesData tmp = mCurrentTiles;
				mCurrentTiles = mNewTiles;
				mNewTiles = tmp;

				mUpdateCnt++;
			} finally {
				MapRenderer.tilelock.unlock();
			}

			// Log.d(TAG, "tiles: " + tileCounter + " " + BufferObject.counter
			// + " sum:" + (tileCounter + BufferObject.counter));
		}

		if (mJobList.size() > 0) {
			updateTileDistances(mJobList, mapPosition);
			Collections.sort(mJobList);

			// sets tiles to isLoading = true
			mMapView.addJobs(mJobList);

			return true;
		}
		return false;
	}

	/**
	 * @param x
	 *            ...
	 * @param y
	 *            ...
	 * @param zoomLevel
	 *            ...
	 * @param zdir
	 *            ...
	 * @return ...
	 */

	/* package */static MapTile addTile(int x, int y, byte zoomLevel, int zdir) {
		MapTile tile;

		tile = QuadTree.getTile(x, y, zoomLevel);

		if (tile == null) {
			tile = new MapTile(x, y, zoomLevel);
			QuadTree.add(tile);

			mTiles.add(tile);
			mJobList.add(tile);
			tileCounter++;
		} else if (!tile.isActive()) {
			mJobList.add(tile);
		}
		// mNewTiles.tiles[tiles++] = tile;

		// if (fetchChildren) {
		// byte z = (byte) (zoomLevel + 1);
		// for (int i = 0; i < 4; i++) {
		// int cx = (xx << 1) + (i % 2);
		// int cy = (yy << 1) + (i >> 1);
		//
		// MapTile c = QuadTree.getTile(cx, cy, z);
		//
		// if (c == null) {
		// c = new MapTile(cx, cy, z);
		//
		// QuadTree.add(c);
		// mTiles.add(c);
		// }
		//
		// if (!c.isActive()) {
		// mJobList.add(c);
		// }
		// }
		// }

		// if (fetchParent || (!fetchProxy && zdir > 0 && zoomLevel > 0)) {

		// if (zdir > 0 && zoomLevel > 0) {
		// // prefetch parent
		// MapTile p = tile.rel.parent.tile;
		//
		// if (p == null) {
		// p = new MapTile(x >> 1, y >> 1, (byte) (zoomLevel - 1));
		//
		// QuadTree.add(p);
		// mTiles.add(p);
		// mJobList.add(p);
		//
		// } else if (!p.isActive()) {
		// if (!mJobList.contains(p))
		// mJobList.add(p);
		// }
		// }
		return tile;
	}

	private static void clearTile(MapTile t) {

		t.newData = false;
		t.isLoading = false;
		t.isReady = false;

		if (t.layers != null) {
			t.layers.clear();
			t.layers = null;
		}

		TextItem.release(t.labels);

		if (t.vbo != null) {
			BufferObject.release(t.vbo);
			t.vbo = null;
		}
		// if (t.texture != null)
		// t.texture.tile = null;

		tileCounter--;
		QuadTree.remove(t);
	}

	private static void updateTileDistances(ArrayList<?> tiles, MapPosition mapPosition) {
		int h = (Tile.TILE_SIZE >> 1);
		byte zoom = mapPosition.zoomLevel;
		long x = (long) mapPosition.x;
		long y = (long) mapPosition.y;
		// long center = Tile.TILE_SIZE << (zoom - 1);
		int diff;
		long dx, dy;

		// TODO this could need some fixing, and optimization
		// to consider move/zoom direction

		for (int i = 0, n = tiles.size(); i < n; i++) {
			JobTile t = (JobTile) tiles.get(i);
			diff = (t.zoomLevel - zoom);

			if (diff == 0) {
				dx = (t.pixelX + h) - x;
				dy = (t.pixelY + h) - y;
				// t.distance = ((dx > 0 ? dx : -dx) + (dy > 0 ? dy : -dy)) *
				// 0.25f;
				// dx %= center;
				// dy %= center;
				t.distance = FloatMath.sqrt((dx * dx + dy * dy)) * 0.25f;
			} else if (diff > 0) {
				// tile zoom level is child of current

				if (diff < 3) {
					dx = ((t.pixelX + h) >> diff) - x;
					dy = ((t.pixelY + h) >> diff) - y;
				}
				else {
					dx = ((t.pixelX + h) >> (diff >> 1)) - x;
					dy = ((t.pixelY + h) >> (diff >> 1)) - y;
				}
				// dx %= center;
				// dy %= center;
				// t.distance = ((dx > 0 ? dx : -dx) + (dy > 0 ? dy : -dy));
				t.distance = FloatMath.sqrt((dx * dx + dy * dy));

			} else {
				// tile zoom level is parent of current
				dx = ((t.pixelX + h) << -diff) - x;
				dy = ((t.pixelY + h) << -diff) - y;
				// dx %= center;
				// dy %= center;
				// t.distance = ((dx > 0 ? dx : -dx) + (dy > 0 ? dy : -dy)) *
				// (-diff * 0.5f);
				t.distance = FloatMath.sqrt((dx * dx + dy * dy)) * (-diff * 0.5f);
			}
		}
	}

	private static void limitCache(MapPosition mapPosition, int remove) {
		int size = mTiles.size();

		// remove tiles that were never loaded
		for (int i = 0; i < size;) {
			MapTile t = mTiles.get(i);
			// make sure tile cannot be used by GL or MapWorker Thread
			if (t.isLocked() || t.isActive()) {
				i++;
			} else {
				clearTile(t);
				mTiles.remove(i);
				remove--;
				size--;
			}
		}

		if (remove <= 0)
			return;

		updateTileDistances(mTiles, mapPosition);
		Collections.sort(mTiles);

		for (int i = 1; i < remove; i++) {
			MapTile t = mTiles.remove(size - i);
			// synchronized (t) {

			if (t.isLocked()) {
				// dont remove tile used by GLRenderer
				Log.d(TAG, "X not removing " + t + " " + t.distance);
				mTiles.add(t);
			} else if (t.isLoading) {
				// NOTE: if we add tile back and set loading=false, on next
				// limitCache the tile will be removed. clearTile could
				// interfere with TileGenerator. so clear in passTile()
				// instead.
				// ... no, this does not work either: when set loading to
				// false tile could be added to load queue while still
				// processed in TileGenerator => need tile.cancel flag.
				// t.isLoading = false;
				mTiles.add(t);
				Log.d(TAG, "X cancel loading " + t + " " + t.distance);
			} else {
				clearTile(t);
			}
			// }
		}
	}

	private static void limitLoadQueue() {
		int size = mTilesLoaded.size();

		if (size < MAX_TILES_IN_QUEUE)
			return;

		synchronized (mTilesLoaded) {

			// remove tiles already uploaded to vbo
			for (int i = 0; i < size;) {
				MapTile t = mTilesLoaded.get(i);
				// t.rel == null means tile was removed in limitCache
				if (!t.newData || t.rel == null) {
					mTilesLoaded.remove(i);
					size--;
					continue;
				}
				i++;
			}

			if (size < MAX_TILES_IN_QUEUE)
				return;

			// clear loaded but not used tiles
			for (int i = 0, n = size - MAX_TILES_IN_QUEUE / 2; i < n; n--) {

				MapTile t = mTilesLoaded.get(i);
				// synchronized (t) {
				if (t.isLocked()) {
					// Log.d(TAG, "keep unused tile data: " + t + " " +
					// t.isActive);
					i++;
					continue;
				}

				// Log.d(TAG, "remove unused tile data: " + t);
				mTilesLoaded.remove(i);
				mTiles.remove(t);
				clearTile(t);
			}
			// }
		}
	}

	/**
	 * called from MapWorker Thread when tile is loaded by TileGenerator
	 * 
	 * @param jobTile
	 *            ...
	 * @return ...
	 */
	public synchronized boolean passTile(JobTile jobTile) {
		MapTile tile = (MapTile) jobTile;

		// if (!tile.isLoading) {
		// // no one should be able to use this tile now, TileGenerator passed
		// // it, GL-Thread does nothing until newdata is set.
		// Log.d(TAG, "passTile: canceled " + tile);
		// return true;
		// }

		// synchronized (tile) {
		if (tile.vbo == null) {
			tile.vbo = BufferObject.get();

			if (tile.vbo == null) {
				Log.d(TAG, "no VBOs left for " + tile);
				tile.isLoading = false;
				return false;
			}
		} else
			Log.d(TAG, "tile loaded before " + tile);

		tile.newData = true;
		tile.isLoading = false;
		// }

		if (!MapView.debugFrameTime)
			requestRender();

		synchronized (mTilesLoaded) {
			if (!mTilesLoaded.contains(tile))
				mTilesLoaded.add(tile);
		}

		return true;
	}

	// public void setRenderTheme(RenderTheme t) {
	// if (mRenderer != null)
	// mRenderer.setRenderTheme(t);
	//
	// }

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {

		Log.d(TAG, "onSizeChanged" + w + " " + h);
		mWidth = w;
		mHeight = h;

		if (mWidth > 0 && mHeight > 0)
			mInitial = true;

		super.onSizeChanged(w, h, oldw, oldh);
	}
}
