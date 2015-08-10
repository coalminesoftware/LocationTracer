package com.coalminesoftware.locationtracer.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * A location store that retains no more than a given number of locations.  Once the store's capacity is reached, excess
 * locations are removed in the order they were offered to the store.
 * 
 * @param <StorageLocation>
 */
public class InMemoryLocationStore<StorageLocation> implements LocationStore<StorageLocation> {
	private Deque<StorageLocation> locations = new LinkedList<StorageLocation>();
	private int locationCountLimit;

	public InMemoryLocationStore(int locationCountLimit) {
		this.locationCountLimit = locationCountLimit;
	}

	@Override
	public void offerLocation(StorageLocation location) {
		locations.addLast(location);

		while(locations.size() > locationCountLimit) {
			locations.removeFirst();
		}
	}

	@Override
	public int getLocationCount() {
		return locations.size();
	}

	@Override
	public List<StorageLocation> getLocations() {
		return new ArrayList<>(locations);
	}

	@Override
	public void removeLocations(Collection<StorageLocation> locations) {
		this.locations.removeAll(locations);
	}
}
