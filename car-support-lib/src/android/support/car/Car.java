/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.car;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.car.app.CarActivity;
import android.support.car.content.pm.CarPackageManager;
import android.support.car.hardware.CarSensorManager;
import android.support.car.media.CarAudioManager;
import android.support.car.navigation.CarNavigationStatusManager;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/**
 * Top-level car API that provides access to all car services and data available in the platform.
 * Developers may create their own instance of {@link Car}, or use the {@link CarActivity#getCar()}
 * method when using a CarActivity.
 */
public class Car {

    /**
     * Service name for {@link CarSensorManager}, to be used in {@link #getCarManager(String)}.
     */
    public static final String SENSOR_SERVICE = "sensor";

    /**
     * Service name for {@link CarInfoManager}, to be used in {@link #getCarManager(String)}.
     */
    public static final String INFO_SERVICE = "info";

    /**
     * Service name for {@link CarAppFocusManager}.
     */
    public static final String APP_FOCUS_SERVICE = "app_focus";

    /**
     * Service name for {@link CarPackageManager}.
     */
    public static final String PACKAGE_SERVICE = "package";

    /**
     * Service name for {@link CarAudioManager}.
     */
    public static final String AUDIO_SERVICE = "audio";
    /**
     * Service name for {@link CarNavigationStatusManager}.
     * @hide
     */
    public static final String CAR_NAVIGATION_SERVICE = "car_navigation_service";
    /**
     * Service name for {@link CarNavigationStatusManager}.
     */
    public static final String NAVIGATION_STATUS_SERVICE = "car_navigation_service";

    /**
     * Type of car connection: car emulator, no physical connection.
     */
    public static final int CONNECTION_TYPE_EMULATOR = 0;
    /**
     * Type of car connection: connected to a car via USB.
     */
    public static final int CONNECTION_TYPE_USB = 1;
    /**
     * Type of car connection: connected to a car via Wi-Fi.
     */
    public static final int CONNECTION_TYPE_WIFI = 2;
    /**
     * Type of car connection: on-device car emulator, for development (such as Local Head Unit).
     */
    public static final int CONNECTION_TYPE_ON_DEVICE_EMULATOR = 3;
    /**
     * Type of car connection: car emulator, connected over ADB (such as Desktop Head Unit).
     */
    public static final int CONNECTION_TYPE_ADB_EMULATOR = 4;
    /**
     * Type of car connection: platform runs directly in car.
     */
    public static final int CONNECTION_TYPE_EMBEDDED = 5;
    /**
     * Unknown type (the support lib is likely out-of-date).
     */
    public static final int CONNECTION_TYPE_UNKNOWN = -1;
    /**
     * Type of car connection: platform runs directly in car with mocked vehicle HAL. Occurs
     * only in a testing environment.
     */
    public static final int CONNECTION_TYPE_EMBEDDED_MOCKING = 6;

    /** @hide */
    @IntDef({CONNECTION_TYPE_EMULATOR, CONNECTION_TYPE_USB, CONNECTION_TYPE_WIFI,
            CONNECTION_TYPE_ON_DEVICE_EMULATOR, CONNECTION_TYPE_ADB_EMULATOR,
            CONNECTION_TYPE_EMBEDDED, CONNECTION_TYPE_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionType {
    }

    /**
     * Permission necessary to access car mileage information.
     * @hide
     */
    public static final String PERMISSION_MILEAGE = "android.car.permission.CAR_MILEAGE";
    /**
     * Permission necessary to access car fuel level.
     * @hide
     */
    public static final String PERMISSION_FUEL = "android.car.permission.CAR_FUEL";
    /**
     * Permission necessary to access car speed.
     * @hide
     */
    public static final String PERMISSION_SPEED = "android.car.permission.CAR_SPEED";
    /**
     * Permission necessary to access a car-specific communication channel.
     */
    public static final String PERMISSION_VENDOR_EXTENSION =
            "android.car.permission.CAR_VENDOR_EXTENSION";
    /**
     * Permission necessary to use {@link android.car.navigation.CarNavigationManager}.
     */
    public static final String PERMISSION_CAR_NAVIGATION_MANAGER =
            "android.car.permission.PERMISSION_CAR_NAVIGATION_MANAGER";


    /**
     * PackageManager.FEATURE_AUTOMOTIVE from M. But redefine here to support L.
     */
    private static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    /**
     * {@link CarServiceLoader} implementation for projected mode. Available only when the
     * projected client library is linked.
     */
    private static final String PROJECTED_CAR_SERVICE_LOADER =
            "com.google.android.gms.car.CarServiceLoaderGms";

    private final Context mContext;
    private final Handler mEventHandler;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    // @GuardedBy("this")
    private int mConnectionState;

    private final ServiceConnectionCallback mServiceConnectionCallback =
            new ServiceConnectionCallback() {
                @Override
                public void onServiceConnected() {
                    synchronized (Car.this) {
                        mConnectionState = STATE_CONNECTED;
                    }
                    mServiceConnectionCallbackClient.onServiceConnected();
                }

                @Override
                public void onServiceDisconnected() {
                    synchronized (Car.this) {
                        if (mConnectionState == STATE_DISCONNECTED) {
                            return;
                        }
                        mConnectionState = STATE_DISCONNECTED;
                    }
                    mServiceConnectionCallbackClient.onServiceDisconnected();
                    connect();
                }

                @Override
                public void onServiceSuspended(int cause) {
                    mServiceConnectionCallbackClient.onServiceSuspended(cause);
                }

                @Override
                public void onServiceConnectionFailed(int cause) {
                    mServiceConnectionCallbackClient.onServiceConnectionFailed(cause);
                }
            };

    private final ServiceConnectionCallback mServiceConnectionCallbackClient;
    private final Object mCarManagerLock = new Object();
    //@GuardedBy("mCarManagerLock")
    private final HashMap<String, CarManagerBase> mServiceMap = new HashMap<>();
    private final CarServiceLoader mCarServiceLoader;


    /**
     * A factory method that creates a Car instance with the given {@code Looper}.
     *
     * @param context The current app context.
     * @param serviceConnectionCallback Receives information when the Car Service is started and
     * stopped.
     * @param handler The handler on which the callback should execute, or null to execute on the
     * service's main thread. Note the service connection listener is always on the main
     * thread regardless of the handler given.
     * @return Car instance if system is in car environment; returns {@code null} otherwise.
     */
    public static Car createCar(Context context,
            ServiceConnectionCallback serviceConnectionCallback, @Nullable Handler handler) {
        try {
            return new Car(context, serviceConnectionCallback, handler);
        } catch (IllegalArgumentException e) {
            // Expected when Car Service loader is not available.
        }
        return null;
    }

    /**
     * A factory method that creates Car instance using the main thread {@link Handler}.
     *
     * @see #createCar(Context, ServiceConnectionCallback, Handler)
     */
    public static Car createCar(Context context,
            ServiceConnectionCallback serviceConnectionCallback) {
        return createCar(context, serviceConnectionCallback, null);
    }

    private Car(Context context, ServiceConnectionCallback serviceConnectionCallback,
            @Nullable Handler handler) {
        mContext = context;
        mServiceConnectionCallbackClient = serviceConnectionCallback;
        if (handler == null) {
            Looper looper = Looper.myLooper();

            if(looper == null){
                looper = Looper.getMainLooper();
            }
            handler = new Handler(looper);
        }
        mEventHandler = handler;

        if (mContext.getPackageManager().hasSystemFeature(FEATURE_AUTOMOTIVE)) {
            mCarServiceLoader =
                    new CarServiceLoaderEmbedded(context, mServiceConnectionCallback,
                            mEventHandler);
        } else {
            mCarServiceLoader = loadCarServiceLoader(PROJECTED_CAR_SERVICE_LOADER, context,
                    mServiceConnectionCallback, mEventHandler);
        }
    }

    private CarServiceLoader loadCarServiceLoader(String carServiceLoaderClassName, Context context,
            ServiceConnectionCallback serviceConnectionCallback, Handler eventHandler)
            throws IllegalArgumentException {
        Class<? extends CarServiceLoader> carServiceLoaderClass = null;
        try {
            carServiceLoaderClass =
                    Class.forName(carServiceLoaderClassName).asSubclass(CarServiceLoader.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Cannot find CarServiceLoader implementation:" + carServiceLoaderClassName, e);
        }
        Constructor<? extends CarServiceLoader> ctor;
        try {
            ctor = carServiceLoaderClass
                    .getDeclaredConstructor(Context.class, ServiceConnectionCallback.class,
                            Looper.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Cannot construct CarServiceLoader, no constructor: "
                    + carServiceLoaderClassName, e);
        }
        try {
            return ctor.newInstance(context, serviceConnectionCallback, eventHandler);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new IllegalArgumentException(
                    "Cannot construct CarServiceLoader, constructor failed for "
                            + carServiceLoaderClass.getName(), e);
        }
    }

    /**
     * Car constructor when CarServiceLoader is already available.
     *
     * @param serviceLoader must be non-null and connected or {@link CarNotConnectedException} will
     * be thrown.
     * @hide
     */
    public Car(@NonNull CarServiceLoader serviceLoader) throws CarNotConnectedException {
        if (!serviceLoader.isConnectedToCar()) {
            throw new CarNotConnectedException();
        }
        mCarServiceLoader = serviceLoader;
        mEventHandler = serviceLoader.getEventHandler();
        mContext = serviceLoader.getContext();

        mConnectionState = STATE_CONNECTED;
        mServiceConnectionCallbackClient = null;
    }

    /**
     * Connect to Car Service. Can be called while disconnected.
     *
     * @throws IllegalStateException if the car is connected or still trying to connect
     * from previous calls.
     */
    public void connect() throws IllegalStateException {
        synchronized (this) {
            if (mConnectionState != STATE_DISCONNECTED) {
                throw new IllegalStateException("already connected or connecting");
            }
            mConnectionState = STATE_CONNECTING;
            mCarServiceLoader.connect();
        }
    }

    /**
     * Disconnect from Car Service. Can be called while disconnected. After disconnect is
     * called, all Car*Managers from this instance become invalid, and {@link
     * Car#getCarManager(String)} returns a different instance if connected again.
     */
    public void disconnect() {
        synchronized (this) {
            if (mConnectionState == STATE_DISCONNECTED) {
                return;
            }
            tearDownCarManagers();
            mConnectionState = STATE_DISCONNECTED;
            mCarServiceLoader.disconnect();
        }
    }

    /**
     * @return Returns {@code true} if this object is connected to the service and {@code false} if
     * otherwise.
     */
    public boolean isConnected() {
        synchronized (this) {
            return mConnectionState == STATE_CONNECTED;
        }
    }

    /**
     * @return Returns {@code true} if this object is still connecting to the service.
     */
    public boolean isConnecting() {
        synchronized (this) {
            return mConnectionState == STATE_CONNECTING;
        }
    }

    /**
     * @return Returns {@code true} if car is connected to the Car Service. In some car
     * environments, being connected to the service does not equate to being connected to a car.
     */
    public boolean isConnectedToCar() {
        return mCarServiceLoader.isConnectedToCar();
    }

    /**
     * Get a car-specific manager. This is modeled after {@link Context#getSystemService(String)}.
     * The returned {@link Object} should be type cast to the desired manager. For example,
     * to get the sensor service, use the following:
     * <pre>{@code CarSensorManager sensorManager =
     *     (CarSensorManager) car.getCarManager(Car.SENSOR_SERVICE);}</pre>
     *
     * @param serviceName Name of service to create, e.g. {@link #SENSOR_SERVICE}.
     * @return The requested service manager or null if the service is not available.
     */
    public Object getCarManager(String serviceName)
            throws CarNotConnectedException {
        Object manager = null;
        synchronized (mCarManagerLock) {
            manager = mServiceMap.get(serviceName);
            if (manager == null) {
                manager = mCarServiceLoader.getCarManager(serviceName);
            }
            // do not store if it is not CarManagerBase. This can happen when system version
            // is retrieved from this call.
            if (manager != null && manager instanceof CarManagerBase) {
                mServiceMap.put(serviceName, (CarManagerBase) manager);
            }
        }
        return manager;
    }

    /**
     * Return the type of currently connected car.
     *
     * @return One of {@link #CONNECTION_TYPE_USB}, {@link #CONNECTION_TYPE_WIFI},
     * {@link #CONNECTION_TYPE_EMBEDDED}, {@link #CONNECTION_TYPE_ON_DEVICE_EMULATOR},
     * {@link #CONNECTION_TYPE_ADB_EMULATOR}, {@link #CONNECTION_TYPE_EMBEDDED_MOCKING},
     * {@link #CONNECTION_TYPE_UNKNOWN}.
     * @throws CarNotConnectedException
     */
    @ConnectionType
    public int getCarConnectionType() throws CarNotConnectedException {
        return mCarServiceLoader.getCarConnectionType();
    }

    /**
     * Register a {@link CarConnectionCallback}. Avoid reregistering callbacks. If a callback is
     * reregistered, it may receive duplicate calls to {@link CarConnectionCallback#onConnected}.
     * @param listener The listener to register.
     *
     * @throws IllegalStateException if service is not connected.
     */
    public void registerCarConnectionCallbacks(CarConnectionCallback listener)
            throws IllegalStateException, CarNotConnectedException {
        assertCarConnection();
        mCarServiceLoader.registerCarConnectionCallback(listener);
    }

    /**
     * Unregister a {@link CarConnectionCallback}. When this method is called from a
     * thread other than the client's looper thread, there is no guarantee the unregistered
     * listener will not receive callbacks after this method returns.
     * @param listener The listener to unregister.
     */
    public void unregisterCarConnectionCallbacks(CarConnectionCallback listener) {
        mCarServiceLoader.unregisterCarConnectionCallback(listener);
    }

    private synchronized void assertCarConnection() throws IllegalStateException {
        if (!mCarServiceLoader.isConnectedToCar()) {
            throw new IllegalStateException("not connected");
        }
    }

    private void tearDownCarManagers() {
        synchronized (mCarManagerLock) {
            for (CarManagerBase manager : mServiceMap.values()) {
                manager.onCarDisconnected();
            }
            mServiceMap.clear();
        }
    }
}
