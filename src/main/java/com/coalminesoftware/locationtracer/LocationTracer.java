package com.coalminesoftware.locationtracer;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;

import com.coalminesoftware.locationtracer.alarm.IrregularRecurringAlarm;
import com.coalminesoftware.locationtracer.alarm.RecurringAlarm;
import com.coalminesoftware.locationtracer.caching.LocationStore;
import com.coalminesoftware.locationtracer.listener.CachingLocationListener;
import com.coalminesoftware.locationtracer.listener.DefaultLocationListener;
import com.coalminesoftware.locationtracer.provider.LocationProviderDeterminationStrategy;
import com.coalminesoftware.locationtracer.provider.SimpleLocationProviderDeterminationStrategy;
import com.coalminesoftware.locationtracer.reporting.LocationReporter;
import com.coalminesoftware.locationtracer.transformation.LocationTransformer;
import com.coalminesoftware.locationtracer.transformation.PassthroughLocationTransformer;

public class LocationTracer<StorageLocation> {
	private Context context;

	private LocationStore<StorageLocation> locationStore;
	private LocationReporter<StorageLocation> locationReporter;

	private CachingLocationListener<?> locationListener;
	private LocationListeningSession locationListeningSession;

	private LocationProviderDeterminationStrategy activeListeningProviderStrategy = new SimpleLocationProviderDeterminationStrategy(LocationManager.GPS_PROVIDER);
	private LocationProviderDeterminationStrategy passiveListeningProviderStrategy = new SimpleLocationProviderDeterminationStrategy(LocationManager.NETWORK_PROVIDER);

	private ReportingSession reportingSession;
	private List<EventListener> eventListeners = new ArrayList<EventListener>();

	private LocationTracer(Context context, LocationTransformer<StorageLocation> locationTransformer,
			LocationStore<StorageLocation> locationStore, LocationReporter<StorageLocation> locationReporter) {
		this.context = context.getApplicationContext();
		this.locationStore = locationStore;
		this.locationReporter = locationReporter;

		locationListener = new CachingLocationListener<StorageLocation>(locationTransformer, locationStore);
	}

	public static LocationTracer<Location> newInstance(Context context, LocationStore<Location> locationStore,
			LocationReporter<Location> locationReporter) {
		return newInstance(context, locationStore, PassthroughLocationTransformer.INSTANCE, locationReporter);
	}

	public static <StorageLocation> LocationTracer<StorageLocation> newInstance(Context context,
			LocationStore<StorageLocation> locationStore, LocationTransformer<StorageLocation> locationTransformer,
			LocationReporter<StorageLocation> locationReporter) {
		return new LocationTracer<StorageLocation>(context, locationTransformer, locationStore, locationReporter);
	}

	public synchronized void startListeningActively(long locationUpdateIntervalDuration) {
		verifyListeningNotInProgress();

		String providerName = activeListeningProviderStrategy.determineLocationProvider(getLocationManager());
		getLocationManager().requestLocationUpdates(providerName, locationUpdateIntervalDuration, 0, locationListener);

		locationListeningSession = new LocationListeningSession();
	}

	public synchronized void startListeningPassively(Long activeLocationRequestInterval, boolean wakeForActiveLocationRequests) {
		verifyListeningNotInProgress();

		getLocationManager().requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, locationListener);

		IrregularRecurringAlarm alarm = null;
		if(activeLocationRequestInterval != null) {			
			alarm = registerActiveLocationUpdate(activeLocationRequestInterval, wakeForActiveLocationRequests);
		}

		locationListeningSession = new LocationListeningSession(alarm);
	}

	private IrregularRecurringAlarm registerActiveLocationUpdate(long activeLocationRequestInterval, boolean wakeForActiveLocationRequests) {
		IrregularRecurringAlarm alarm = new ActiveLocationUpdateAlarm(wakeForActiveLocationRequests, activeLocationRequestInterval);

		alarm.startRecurringAlarm();

		return alarm;
	}

	private void verifyListeningInProgress() {
		if(locationListeningSession == null) {
			throw new IllegalStateException("Cannot stop listening when listening is not in progress.");
		}
	}

	private void verifyListeningNotInProgress() {
		if(locationListeningSession != null) {
			throw new IllegalStateException("Cannot start listening when listening is already in progress.");
		}
	}

	public synchronized void stopListening() {
		verifyListeningInProgress();

		getLocationManager().removeUpdates(locationListener);

		if(locationListeningSession.getActiveLocationUpdateAlarm() != null) {
			locationListeningSession.getActiveLocationUpdateAlarm().stopRecurringAlarm();
		}

		locationListeningSession = null;
	}

	public void startReporting(long reportIntervalDuration, boolean wakeForReport) {
		if(reportingSession != null) {
			throw new IllegalStateException("Cannot start reporting when reporting is already in progress.");
		}

		RecurringAlarm reportingAlarm = new RecurringAlarm(context, reportIntervalDuration, wakeForReport) {
			@Override
			public void handleAlarm(long alarmElapsedRealtime) {
				reportStoredLocations();
			}
		};
		reportingAlarm.startRecurringAlarm();

		reportingSession = new ReportingSession(reportingAlarm);
	}

	public void stopReporting(boolean reportUnreportedLocations) {
		reportingSession.getReportingAlarm().stopRecurringAlarm();
		reportingSession = null;

		if(reportUnreportedLocations) {
			reportStoredLocations();
		}
	}

	public void addEventListener(EventListener listener) {
		eventListeners.add(listener);
	}

	public void removeEventListener(EventListener listener) {
		eventListeners.remove(listener);
	}

	private void reportStoredLocations() {
		if(locationStore.getLocationCount() > 0) {
			List<StorageLocation> locations = locationStore.getLocations();
			List<StorageLocation> reportedLocations = locationReporter.reportLocations(locations);
			locationStore.removeLocations(reportedLocations);
		}
	}

	private LocationManager getLocationManager() {
		return (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
	}

	private static class ReportingSession {
		private RecurringAlarm reportingAlarm;

		public ReportingSession(RecurringAlarm reportingAlarm) {
			this.reportingAlarm = reportingAlarm;
		}

		public RecurringAlarm getReportingAlarm() {
			return reportingAlarm;
		}
	}

	private static class LocationListeningSession {
		private IrregularRecurringAlarm activeLocationUpdateAlarm;

		public LocationListeningSession() { }

		public LocationListeningSession(IrregularRecurringAlarm activeLocationUpdateAlarm) {
			this.activeLocationUpdateAlarm = activeLocationUpdateAlarm;
		}

		public IrregularRecurringAlarm getActiveLocationUpdateAlarm() {
			return activeLocationUpdateAlarm;
		}
	}

	public class ActiveLocationUpdateAlarm extends IrregularRecurringAlarm {
		private long locationUpdateIntervalDuration;

		public ActiveLocationUpdateAlarm(boolean wakeForAlarm, long locationUpdateIntervalDuration) {
			super(context, wakeForAlarm);

			this.locationUpdateIntervalDuration = locationUpdateIntervalDuration;
		}

		@Override
		protected long determineNextAlarmDelay(long alarmTime) {
			Long timeElapsed = determineTimeElapsedSinceLastLocationUpdate(alarmTime);
			return timeElapsed == null || hasAlarmExpired(timeElapsed)?
					locationUpdateIntervalDuration :
					determineRemainingTime(timeElapsed);
		}

		@Override
		public void handleAlarm(long alarmTime) {
			Long timeElapsed = determineTimeElapsedSinceLastLocationUpdate(alarmTime);
			if(timeElapsed == null || hasAlarmExpired(timeElapsed)) {
				// Since a passive listener is already listening for the location update that this request hopes to
				// cause, a no-op location listener is used to avoid offering duplicate updates to the cache.
				getLocationManager().requestSingleUpdate(
						passiveListeningProviderStrategy.determineLocationProvider(getLocationManager()),
						DefaultLocationListener.INSTANCE,
						Looper.myLooper());
			}
		}

		private Long determineTimeElapsedSinceLastLocationUpdate(long alarmElapsedRealtime) {
			// TODO Should this be based on the time that the last location was offered or accepted?
			Long lastLocationObservationTimestamp = locationListener.getLastLocationObservationTime();
			return lastLocationObservationTimestamp == null?
					null :
					alarmElapsedRealtime - lastLocationObservationTimestamp;
		}

		private long determineRemainingTime(long timeElapsed) {
			return locationUpdateIntervalDuration - timeElapsed;
		}

		private boolean hasAlarmExpired(long timeElapsed) {
			return timeElapsed >= locationUpdateIntervalDuration;
		}
	}
}


