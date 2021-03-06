package interdroid.swan.sensors.impl;

import java.io.IOException;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import interdroid.swan.R;
import interdroid.swan.sensors.AbstractConfigurationActivity;
import interdroid.swan.sensors.AbstractSwanSensor;

@TargetApi(Build.VERSION_CODES.KITKAT)
public class StepDetectorSensor extends AbstractSwanSensor implements SensorEventListener{
	public static final String TAG = "StepDetectorSensor";
	
	public static class ConfigurationActivity extends AbstractConfigurationActivity {
		@Override
		public final int getPreferencesXML() {
			return R.xml.stepdetector_preferences;
		}
	}
		
	private Sensor mStepDetector;
	private SensorManager mSensorManager;
	private PendingIntent mSensorUpdatePIntent;
	
	/** Value of ACCURACY must be one of SensorManager.SENSOR_DELAY_* */
	public static final String ACCURACY = "accuracy";
	protected static final int HISTORY_SIZE = 30;
	public static final String ACTION_FLUSH_SENSOR = "interdroid.swan.sensors.impl.StepDetectorSensor.FlushSensorData";
	
	/**
	 * Is step detected
	 */
	public static final String IS_STEP_DETECTED = "is_step_detected";
	
	@Override
	public void register(String id, String valuePath, Bundle configuration)
			throws IOException {
		updateAccuracy();
	}
	
	@Override
	public void unregister(String id) {
		updateAccuracy();		
	}

	@Override
	public void onDestroySensor() {
		mSensorManager.unregisterListener(this);
		super.onDestroySensor();
	}

	@Override
	public void onConnected() {
		SENSOR_NAME = "Step Detector Sensor";
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mStepDetector = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
		if (mStepDetector == null)
			Log.e(TAG, "No step detector sensor found on device!");		
	}

	@Override
	public void initDefaultConfiguration(Bundle defaults) {
		defaults.putInt(ACCURACY, SensorManager.SENSOR_DELAY_NORMAL);
	}

	@Override
	public String[] getValuePaths() {
		return new String[] { IS_STEP_DETECTED };
	}
	
	/**
     * Calculates the maximum sensor report interval, based on the
     * hardware sensor events buffer size, to avoid dropping steps.
     *
     * @param stepCounter The Step Counter sensor
     *
     * @return Returns the optimal update interval, in milliseconds
     */
    private static int calcSensorReportInterval(Sensor stepCounter) {
        // We assume that, normally, a person won't do more than
        // two steps in a second (worst case: running)
        final int fifoSize = stepCounter.getFifoReservedEventCount();
        if (fifoSize > 1) {
            return (fifoSize / 2) * 1000;
        }

        // In this case, the device seems not to have an HW-backed
        // sensor events buffer. We're assuming that there's no
        // batching going on, so we don't really need the alarms.
        return 0;
    }
    
    /**
     * Sets up a wakelock-based alarm that allows this service
     * to retrieve sensor events before they're dropped out of
     * the FIFO buffer.
     */
    private void setupSensorUpdateAlarm(int interval) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + interval,
                                  interval, mSensorUpdatePIntent);
    }

    private void updateAccuracy() {
		mSensorManager.unregisterListener(this);
		if (registeredConfigurations.size() > 0) {

			int highestAccuracy = mDefaultConfiguration.getInt(ACCURACY);
			for (Bundle configuration : registeredConfigurations.values()) {
				if (configuration == null) {
					continue;
				}
				if (configuration.containsKey(ACCURACY)) {
					highestAccuracy = Math
							.min(highestAccuracy,
									Integer.parseInt(configuration
											.getString(ACCURACY)));
				}
			}
			highestAccuracy = Math.max(highestAccuracy, SensorManager.SENSOR_DELAY_FASTEST);
			// We use batching for the step detector sensor
	        final int reportInterval = calcSensorReportInterval(mStepDetector);
	        mSensorManager.registerListener(this, mStepDetector, highestAccuracy,
	                                        null/*reportInterval  /*  micro seconds */);

	        if (reportInterval > 0) {
	            Log.i(TAG, "Setting up batched data retrieval every " + reportInterval + " ms");
	            setupSensorUpdateAlarm(reportInterval);
	        }
	        else {
	            Log.w(TAG, "This device doesn't support events batching!");
	        }		
		}
	}
		
	/*
	 * @see android.hardware.SensorEventListener
	 */

	@Override
	public void onSensorChanged(SensorEvent event) {
		Log.d(TAG, "on sensor changed");
		if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
			Log.v(TAG, "New step detector event. Value: " + (int)event.values[0]);
			long now = System.currentTimeMillis();
			putValueTrimSize(IS_STEP_DETECTED, null, now, event.values[0]);
		}		
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		Log.v(TAG, "Sensor accuracy changed. New value: " + accuracy);		
	}

}
