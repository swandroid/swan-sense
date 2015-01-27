package interdroid.swan.sensors;

import interdroid.swan.swansong.TimestampedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nl.sense_os.platform.TrivialSensorRegistrator;
import nl.sense_os.service.R;
import nl.sense_os.service.commonsense.SensorRegistrator;
import nl.sense_os.service.constants.SenseDataTypes;
import nl.sense_os.service.constants.SensePrefs;
import nl.sense_os.service.constants.SensorData.DataPoint;
import nl.sense_os.service.storage.LocalStorage;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public abstract class AbstractSwanSensor extends AbstractSensorBase{
	public static String TAG = "Abstract Sensor";
	
	/**
	 * The map of values for this sensor.
	 */
	private final Map<String, List<TimestampedValue>> values = new HashMap<String, List<TimestampedValue>>();
	
	/**
	 * Sensor specific name, as it will appear on Sense. 
	 * Each sensor implementation should set this field 
	 */
	protected static String SENSOR_NAME;
	
	/**
	 * Timestamp indicating when the last flush occurred
	 */
	private long mLastFlushed = 0;
	
	private long mReadings = 0;
	private long mLastReadingTimestamp = 0;

	/**
	 * @return the values
	 */
	public final Map<String, List<TimestampedValue>> getValues() {
		return values;
	}
		
	@Override
	public void init() {    
		final Context context = this;
		for (final String valuePath : VALUE_PATHS) {
			expressionIdsPerValuePath.put(valuePath, new ArrayList<String>());
			getValues().put(valuePath,
							Collections.synchronizedList(new ArrayList<TimestampedValue>()));
		}
		
		new Thread() {        	
            @Override
            public void run() {
				// register the sensor
		        SensorRegistrator registrator = new TrivialSensorRegistrator(context);
		        for (final String valuePath : VALUE_PATHS) {
		        	registrator.checkSensor(SENSOR_NAME, SENSOR_NAME, SenseDataTypes.FLOAT, "valuePath= "+valuePath, "", null, null);
		        }
            }
		}.start();		
	}

	/**
	 * Adds a value for the given value path to the history.
	 * 
	 * @param valuePath
	 *            the value path
	 * @param now
	 *            the current time
	 * @param value
	 *            the value
	 * @param historySize
	 *            the history size
	 */
	protected final void putValueTrimSize(final String valuePath,
			final String id, final long now, final Object value /*, final int historySize*/) {
		updateReadings(now);
		try {		
		    getValues().get(valuePath).add(new TimestampedValue(value, now));
		}
		catch(OutOfMemoryError e){
			Log.d(TAG, "OutOfMemoryError");
			onDestroySensor();
		}
		checkMemoryAtRuntime();
		
		if (id != null) {
			notifyDataChangedForId(id);
		} else {
			notifyDataChanged(valuePath);
		}
	}

	/**
	 * Check memory used and flush when low on free memory
	 */
	static boolean flush = true;
	public void checkMemoryAtRuntime(){
		long usedMemory = Runtime.getRuntime().totalMemory()-
				Runtime.getRuntime().freeMemory();
		if (usedMemory > Runtime.getRuntime().maxMemory() / 5){
			if (flush == true){
				Log.d(TAG, "Flush to the database");
				flush = false;
				
				SharedPreferences mainPrefs = getSharedPreferences(SensePrefs.MAIN_PREFS, Context.MODE_PRIVATE);
		        String storageOption = mainPrefs.getString(SensePrefs.Main.Advanced.STORAGE, "Remote Storage");
		        Log.d(TAG, "storage option: " + storageOption); 
		        if (0 == storageOption.compareTo("None"))
		        	clearData();
		        else
		        	flushData();
			}
		}
		else {
			if (flush == false){
				Log.d(TAG, "Memory freed");
				flush = true;
			}
		}
	}

	/**
	 * Adds a value for the given value path to the history.
	 * 
	 * @param valuePath
	 *            the value path
	 * @param now
	 *            the current time
	 * @param value
	 *            the value
	 * @param historyLength
	 *            the history length
	 */
	protected final void putValueTrimTime(final String valuePath,
			final String id, final long now, final Object value,
			final long historyLength) {
		updateReadings(now);
		getValues().get(valuePath).add(new TimestampedValue(value, now));
		trimValueByTime(now - historyLength);
		if (id != null) {
			notifyDataChangedForId(id);
		} else {
			notifyDataChanged(valuePath);
		}
	}
	
	private void updateReadings(long now) {
		if (now != mLastReadingTimestamp) {
			mReadings++;
			mLastReadingTimestamp = now;
		}
	}

	/**
	 * Trims values past the given expire time.
	 * 
	 * @param expire
	 *            the time to trim after
	 */
	private final void trimValueByTime(final long expire) {
		for (String valuePath : VALUE_PATHS) {
			List<TimestampedValue> values = getValues().get(valuePath);
			while ((values.size() > 0 && values.get(0)
					.getTimestamp() < expire)) {
				values.remove(0);
			}
		}
	}
	
	@Override
	public final List<TimestampedValue> getValues(final String id,
			final long now, final long timespan) {
		/*
		 * First check if we have all data in memory, otherwise fetch it from upper storage layer
		 */		
		List<TimestampedValue> valuesForTimeSpan = null;
		if (mLastFlushed > (now-timespan) )
			getLocalValues(now-timespan, mLastFlushed);
		try {
			valuesForTimeSpan = getValuesForTimeSpan(getValues().get(registeredValuePaths.get(id)),
				now, timespan);
		}
		catch(OutOfMemoryError e){
			Log.e(TAG, "OutOfMemoryError");
			onDestroySensor();
		}
		return valuesForTimeSpan;
	}
	
	/**
     * Store data from memory to local storage. Called when all configurations unregistered from this sensor, 
     * causing the service to destroy.
     */
    public void flushData() {	
    	Log.d(TAG, "Flush data to db");
    	for (final String valuePath: getValues().keySet()){
    		if (getValues().get(valuePath).size() == 0){
    			Log.d(TAG, "No values to send for value path" + valuePath);
    			continue;
    		}
            insertDataInLocalStorage(valuePath);
    	}
    }
	
    /**
	 * Inserts all sensor values from corresponding valuePath into the local database
	 * All values are insert in a single batch
	 * The values are removed from the hash map, clearing memory
	 * @param valuePath
	 */
	private void insertDataInLocalStorage(String valuePath){
		int size = getValues().get(valuePath).size();
		ArrayList<ContentValues> vals = new ArrayList<ContentValues>();
		for (int i = size-1; i >= 0; i --){
			TimestampedValue tsVal = getValues().get(valuePath).get(i);
			if (i == size-1){
				mLastFlushed = tsVal.getTimestamp();	//the latest timestamp is of the last item in the list
			}
			
			ContentValues val = new ContentValues();
			val.put(DataPoint.SENSOR_NAME, SENSOR_NAME);
			val.put(DataPoint.DISPLAY_NAME, SENSOR_NAME);
			val.put(DataPoint.SENSOR_DESCRIPTION, "valuePath= "+valuePath);
			val.put(DataPoint.VALUE_PATH, valuePath);
			val.put(DataPoint.DATA_TYPE, SenseDataTypes.FLOAT);
//			val.put(DataPoint.DEVICE_UUID, null);
			val.put(DataPoint.TIMESTAMP, tsVal.getTimestamp());
			val.put(DataPoint.VALUE, tsVal.getValue().toString());
			
			SharedPreferences mainPrefs = getSharedPreferences(SensePrefs.MAIN_PREFS, Context.MODE_PRIVATE);
	         String storageOption = mainPrefs.getString(SensePrefs.Main.Advanced.STORAGE, "Remote Storage");
	         Log.d(TAG, "storage option: " + storageOption); 
	         if (0 == storageOption.compareToIgnoreCase("Remote storage"))
	        	 val.put(DataPoint.TRANSMIT_STATE, 0);
        	 else
        		 val.put(DataPoint.TRANSMIT_STATE, 1);
	        	 
			
			vals.add(val);
		}		
		getValues().get(valuePath).clear();
		bulkInsertToLocalStorage(vals);
	}
	
	/**
	 * Insert data in the database in a separate thread
	 * @param cvalues -data to be inserted
	 */
	public void bulkInsertToLocalStorage(final ArrayList<ContentValues> cvalues) {
		final int sizeToInsert = cvalues.size();
		new Thread() {        	
            @Override
            public void run() {
            	
            	int count = LocalStorage.getInstance(getApplicationContext()).bulkInsert(cvalues);
            	if (count == sizeToInsert){
            		Log.d(TAG, "data (count = " + count + ") flushed successfully");
            	}
            	else 
            		Log.d(TAG, "inserted " + count + " elements in the db instead of " + sizeToInsert);
            }
        }.start();
	}
    /**
     * Get data from the db, collected in the period (start,end)  and put it in the hash map with values
     */
    protected void getLocalValues(final long start, final long end) {
        try {
        	String[] projection = new String[] {DataPoint.VALUE_PATH, DataPoint.TIMESTAMP, DataPoint.VALUE };
            String where = "(" + DataPoint.TIMESTAMP + " >= " + String.valueOf(start) +
                			" AND " + DataPoint.TIMESTAMP + " <= " + String.valueOf(end) + ")";
            Uri uri = Uri.parse("content://" + getResources().getString(R.string.local_storage_authority) + DataPoint.CONTENT_URI_PATH);
            
            //sort order matter because latest values should go last
            String sortOrder = DataPoint.TIMESTAMP + " ASC";
        	Cursor c = LocalStorage.getInstance(getApplicationContext()).query(uri, projection, where, null, sortOrder);
            if (null == c || !c.moveToFirst()){
            	Log.d(TAG, "Nothing in the db");	//fetch it from remote storage?
            	return;
            }    
            Log.d(TAG, c.getCount()+" items retrieved from db");
            
            while (!c.isAfterLast()) {
                getValues().get(c.getString(c.getColumnIndex(DataPoint.VALUE_PATH)))
                .add(new TimestampedValue(c.getString(c.getColumnIndex(DataPoint.VALUE)), c.getLong(c.getColumnIndex(DataPoint.TIMESTAMP))));
                c.moveToNext();
            }
            c.close();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Failed to query remote data", e);
        }
    }
    
	@Override
	public long getReadings() {
		return mReadings;
	}
	
	/**
	 * Checks whether there are any readings in memory
	 */
	public boolean isMemoryEmpty(){
		for (List<TimestampedValue> readingsList : getValues().values()) {
		    if (!readingsList.isEmpty())
		    	return false;
		}
		return true;
	}
	
	/**
	 * Clears the values from the lit
	 */
	private void clearData(){
		for (List<TimestampedValue> readingsList : getValues().values()) 
		    readingsList.clear();
	}
	
	@Override
	public void onDestroySensor() {
		if (registeredConfigurations.size() == 0)
			flushData();	
	}	
}
