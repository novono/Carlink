package com.opencarlink.receiver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class WirelessCarLinkEngine {
    interface Callback {
        void onState(int stage, String state, String detail, String pin);

        void onLog(String message);

        void onFatal(String message);

        void onDisconnected(String reason);
    }

    private static final int AUTH_PORT = 57209;
    private static final int DEFAULT_MTU = 23;

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private String requestedSsid = IccoaProtocol.randomSsid();
    private String requestedPassphrase = IccoaProtocol.randomPassphrase();
    private volatile String pin = IccoaProtocol.randomPin();
    private IccoaProtocol.Identity identity;
    private final Set<Socket> authClients = ConcurrentHashMap.newKeySet();
    private final Map<Socket, AndroidAuthStore> authStores = new ConcurrentHashMap<>();
    private final Map<Socket, IccoaAuthSession> authSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private boolean sawP2pClient;
    private boolean groupQueryPending;
    private long p2pLossSince;
    private final Runnable verifyP2pLoss = this::requestGroupInfo;
    private Socket activeAuthClient;
    private final Map<String, ByteArrayOutputStream> preparedWrites = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> negotiatedMtus = new ConcurrentHashMap<>();
    private final Set<String> subscribedDevices = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingServerInfo = ConcurrentHashMap.newKeySet();
    private final Set<String> indicatingDevices = ConcurrentHashMap.newKeySet();

    private WifiP2pManager wifiManager;
    private WifiP2pManager.Channel wifiChannel;
    private WifiManager.WifiLock wifiLock;
    private BroadcastReceiver wifiReceiver;
    private WifiP2pGroup group;
    private String ssid = "";
    private String passphrase = "";
    private String address = "";
    private int frequency;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic serverCharacteristic;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private ServerSocket authListener;
    private WirelessSessionChannels sessionChannels;
    private volatile boolean stopped;
    private boolean bleStarted;

    WirelessCarLinkEngine(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        this.identity = IccoaProtocol.loadIdentity(context);
    }

    void start() {
        callback.onLog("创建 Wi-Fi Direct autonomous GO");
        try {
            acquireLowLatencyWifiLock();
            wifiManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (wifiManager == null) {
                fail("系统没有 Wi-Fi Direct 服务");
                return;
            }
            wifiChannel = wifiManager.initialize(context, Looper.getMainLooper(), () -> {
                if (!stopped) {
                    fail("Wi-Fi Direct channel 已断开");
                }
            });
            registerWifiReceiver();
            removeOldGroupThenCreate();
        } catch (SecurityException error) {
            fail("Wi-Fi Direct 权限被系统拒绝：" + error.getMessage());
        } catch (RuntimeException error) {
            fail("Wi-Fi Direct 初始化失败：" + error.getMessage());
        }
    }

    void stop() {
        stop(cleared -> { });
    }

    void stop(Consumer<Boolean> released) {
        WirelessSessionChannels channels;
        synchronized (this) {
            if (stopped) {
                released.accept(false);
                return;
            }
            stopped = true;
            channels = sessionChannels;
            sessionChannels = null;
            for (Socket client : authClients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            authClients.clear();
            for (IccoaAuthSession auth : authSessions.values()) { auth.clear(); }
            authSessions.clear();
            for (AndroidAuthStore store : authStores.values()) { store.clear(); }
            authStores.clear();
            activeAuthClient = null;
        }
        main.removeCallbacksAndMessages(null);
        stopBluetooth();
        closeAuthListener();
        releaseLowLatencyWifiLock();
        io.shutdownNow();
        BroadcastReceiver receiver = wifiReceiver;
        wifiReceiver = null;
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        preparedWrites.clear();
        connectedDevices.clear();
        negotiatedMtus.clear();
        subscribedDevices.clear();
        pendingServerInfo.clear();
        indicatingDevices.clear();
        group = null;
        ssid = passphrase = address = pin = requestedSsid = requestedPassphrase = "";
        identity = null;
        frequency = 0;
        WifiP2pManager manager = wifiManager;
        WifiP2pManager.Channel channel = wifiChannel;
        wifiManager = null;
        wifiChannel = null;
        Consumer<Boolean> finished = cleared -> {
            if (channel != null) { channel.close(); }
            released.accept(cleared);
        };
        Runnable releaseNetwork = () -> {
            if (manager != null && channel != null) {
                removeGroupAndAwait(manager, channel, finished);
            } else {
                finished.accept(true);
            }
        };
        if (channels != null) {
            channels.stop(() -> main.post(releaseNetwork));
        } else {
            releaseNetwork.run();
        }
    }

    /** Action success only accepts removal; group info must confirm that removal finished. */
    private void removeGroupAndAwait(WifiP2pManager manager, WifiP2pManager.Channel channel,
            Consumer<Boolean> finished) {
        class Removal {
            final AtomicBoolean complete = new AtomicBoolean();
            final long deadline = SystemClock.elapsedRealtime() + 8_000L;
            final Runnable timeout = () -> finish(false);

            void finish(boolean cleared) {
                if (complete.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout);
                    finished.accept(cleared);
                }
            }

            void remove() {
                if (complete.get()) { return; }
                if (SystemClock.elapsedRealtime() >= deadline) { finish(false); return; }
                try {
                    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() { poll(); }
                        @Override public void onFailure(int reason) { poll(); }
                    });
                } catch (RuntimeException error) { finish(false); }
            }

            void poll() {
                if (complete.get()) { return; }
                try {
                    manager.requestGroupInfo(channel, value -> {
                        if (complete.get()) { return; }
                        if (value == null) {
                            finish(true);
                        } else {
                            main.postDelayed(this::remove, 300L);
                        }
                    });
                } catch (RuntimeException error) { finish(false); }
            }
        }
        Removal removal = new Removal();
        main.postDelayed(removal.timeout, 8_000L);
        removal.remove();
    }

    private void acquireLowLatencyWifiLock() {
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (manager == null) {
            callback.onLog("Wi-Fi 低延迟模式不可用");
            return;
        }
        try {
            WifiManager.WifiLock lock = manager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "OpenCarLink:LowLatency"
            );
            lock.setReferenceCounted(false);
            lock.acquire();
            wifiLock = lock;
            callback.onLog("Wi-Fi 低延迟模式已启用");
        } catch (RuntimeException error) {
            callback.onLog("Wi-Fi 低延迟模式启用失败：" + error.getMessage());
        }
    }

    private void releaseLowLatencyWifiLock() {
        WifiManager.WifiLock lock = wifiLock;
        wifiLock = null;
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void registerWifiReceiver() {
        wifiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (stopped) {
                    return;
                }
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)
                    || WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    requestGroupInfo();
                } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    );
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        fail("系统 Wi-Fi Direct 当前未开启");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(wifiReceiver, filter);
        }
    }

    private void removeOldGroupThenCreate() {
        removeGroupAndAwait(wifiManager, wifiChannel, cleared -> {
            if (stopped) { return; }
            if (cleared) { createGroup(); }
            else { fail("旧 Wi-Fi Direct 群组未释放，请重试"); }
        });
    }

    private void createGroup() {
        if (stopped) {
            return;
        }
        WifiP2pConfig config;
        try {
            config = new WifiP2pConfig.Builder()
                .setNetworkName(requestedSsid)
                .setPassphrase(requestedPassphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(false)
                .build();
        } catch (RuntimeException error) {
            fail("GO 参数不被 Flyme 接受：" + error.getMessage());
            return;
        }
        callback.onState(0, "正在创建 P2P GO", requestedSsid, pin);
        try {
            wifiManager.createGroup(wifiChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    callback.onLog("系统已接受 Wi-Fi Direct GO 创建请求");
                    pollGroupInfo(0);
                }

                @Override
                public void onFailure(int reason) {
                    fail("创建 Wi-Fi Direct GO 失败：" + p2pReason(reason));
                }
            });
        } catch (SecurityException error) {
            fail("创建 GO 时权限被拒绝：" + error.getMessage());
        }
    }

    private void pollGroupInfo(int attempt) {
        if (stopped) {
            return;
        }
        requestGroupInfo();
        if (group == null && attempt < 30) {
            main.postDelayed(() -> pollGroupInfo(attempt + 1), 350L);
        } else if (group == null) {
            fail("Wi-Fi Direct GO 已请求，但 10 秒内没有取得 group info");
        }
    }

    private void requestGroupInfo() {
        if (stopped || wifiManager == null || wifiChannel == null || groupQueryPending) {
            return;
        }
        groupQueryPending = true;
        try {
            wifiManager.requestGroupInfo(wifiChannel, value -> {
                groupQueryPending = false;
                onGroupInfo(value);
            });
        } catch (SecurityException error) {
            groupQueryPending = false;
            fail("读取 P2P group info 被拒绝：" + error.getMessage());
        }
    }

    private synchronized void onGroupInfo(WifiP2pGroup value) {
        if (stopped) { return; }
        if (value == null || !value.isGroupOwner()) {
            if (!ssid.isEmpty()) { confirmP2pLoss("Wi-Fi Direct 连接已断开"); }
            return;
        }
        if (!value.getClientList().isEmpty()) {
            sawP2pClient = true;
        } else if (sawP2pClient) {
            confirmP2pLoss("手机已离开 Wi-Fi Direct");
            return;
        }
        p2pLossSince = 0;
        main.removeCallbacks(verifyP2pLoss);
        group = value;
        String groupSsid = value.getNetworkName();
        String groupPassphrase = value.getPassphrase();
        int groupFrequency = value.getFrequency();
        String groupAddress = interfaceAddress(value.getInterface());
        if (groupSsid == null || groupSsid.isEmpty()
            || groupPassphrase == null || groupPassphrase.isEmpty()
            || groupFrequency <= 0
            || groupAddress.isEmpty()) {
            callback.onLog(
                "等待 GO 参数完整：ssid=" + (groupSsid != null)
                    + " psk=" + (groupPassphrase != null)
                    + " freq=" + groupFrequency
                    + " ip=" + groupAddress
            );
            return;
        }
        if (!ssid.isEmpty()) {
            return;
        }
        ssid = groupSsid;
        passphrase = groupPassphrase;
        frequency = groupFrequency;
        address = groupAddress;
        callback.onLog(
            "P2P GO 就绪：" + ssid + "，" + address + "，" + frequency + " MHz"
        );
        callback.onState(1, "P2P GO 已就绪", address + " · " + frequency + " MHz", pin);
        startAuthListener();
    }

    private void confirmP2pLoss(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (p2pLossSince == 0) { p2pLossSince = now; }
        if (sessionChannels != null && sessionChannels.hasRecentMediaTraffic()) {
            p2pLossSince = now;
        } else if (now - p2pLossSince >= 2_000L) {
            disconnected(reason);
            return;
        }
        main.removeCallbacks(verifyP2pLoss);
        main.postDelayed(verifyP2pLoss, 2_000L);
    }

    private String interfaceAddress(String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) {
            return "";
        }
        try {
            NetworkInterface network = NetworkInterface.getByName(interfaceName);
            if (network == null) {
                return "";
            }
            Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                java.net.InetAddress item = addresses.nextElement();
                if (item instanceof Inet4Address && !item.isLoopbackAddress()) {
                    return item.getHostAddress();
                }
            }
        } catch (IOException error) {
            callback.onLog("读取 GO 接口地址失败：" + error.getMessage());
        }
        return "";
    }

    private void startAuthListener() {
        io.execute(() -> {
            try {
                ServerSocket listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress("0.0.0.0", AUTH_PORT));
                listener.setSoTimeout(500);
                synchronized (this) {
                    if (stopped) { listener.close(); return; }
                    authListener = listener;
                }
                callback.onLog("AUTH 已监听 TCP " + AUTH_PORT);
                main.post(this::startBluetooth);
                while (!stopped) {
                    try {
                        Socket client = listener.accept();
                        try {
                            configureClient(client);
                        } catch (IOException error) {
                            client.close();
                            continue;
                        }
                        synchronized (this) {
                            if (stopped || activeAuthClient != null || sessionChannels != null) {
                                client.close();
                                continue;
                            }
                            activeAuthClient = client;
                            authClients.add(client);
                            try {
                                io.execute(() -> readAuthClient(client));
                            } catch (RejectedExecutionException error) {
                                authClients.remove(client);
                                activeAuthClient = null;
                                client.close();
                            }
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (IOException error) {
                if (!stopped) {
                    fail("AUTH listener 启动失败：" + error.getMessage());
                }
            }
        });
    }

    private void configureClient(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        client.setKeepAlive(true);
        client.setSoTimeout(700);
    }

    private void readAuthClient(Socket client) {
        String peer = client.getInetAddress().getHostAddress();
        callback.onLog("手机已连接 AUTH：" + peer);
        callback.onState(4, "正在认证手机", peer + ":" + client.getPort(), pin);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        AndroidAuthStore store;
        IccoaAuthSession auth;
        synchronized (this) {
            if (stopped) { return; }
            store = new AndroidAuthStore(context);
            auth = new IccoaAuthSession(pin, store);
            authStores.put(client, store);
            authSessions.put(client, auth);
        }
        IccoaAuthSession.StreamDecoder decoder = new IccoaAuthSession.StreamDecoder();
        boolean reportedSuccess = false;
        try (client) {
            while (!stopped) {
                try {
                    int count = client.getInputStream().read(buffer);
                    if (count < 0) {
                        break;
                    }
                    total += count;
                    if (total == count) {
                        callback.onLog("收到首个 AUTH 数据包：" + count + " 字节");
                    }
                    for (byte[] message : decoder.feed(buffer, count)) {
                        IccoaAuthSession.Result result = auth.handle(message);
                        callback.onLog(result.description);
                        if (result.response != null) {
                            client.getOutputStream().write(result.response);
                            client.getOutputStream().flush();
                            callback.onLog("已发送 AUTH_RESPONSE：" + result.response.length + " 字节");
                        }
                        if (auth.isConfirmed() && !reportedSuccess) {
                            reportedSuccess = true;
                            String connectionInfo = auth.connectionInfo();
                            String detail = connectionInfo == null || connectionInfo.isEmpty()
                                ? peer : connectionInfo;
                            callback.onState(5, "无线认证成功", detail, pin);
                            callback.onLog("AUTH_CONFIRM 已完成；正在建立 CONTROL/RTSP/RTP");
                            byte[] key = auth.sessionKey();
                            try {
                                startSessionChannels(phoneAddress(connectionInfo, peer), key);
                            } finally {
                                if (key != null) { Arrays.fill(key, (byte) 0); }
                            }
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            if (!stopped) {
                callback.onLog("AUTH 协议失败：" + error.getMessage());
            }
        } catch (IOException error) {
            if (!stopped) {
                callback.onLog("AUTH 通道结束：" + error.getMessage());
            }
        } finally {
            auth.clear();
            store.clear();
            decoder.clear();
            Arrays.fill(buffer, (byte) 0);
            synchronized (this) {
                authClients.remove(client);
                authStores.remove(client);
                authSessions.remove(client);
                if (activeAuthClient == client) { activeAuthClient = null; }
            }
            if (!stopped && !reportedSuccess) { disconnected("认证连接已结束"); }
        }
    }

    private synchronized void startSessionChannels(String phoneIp, byte[] sessionKey) {
        if (stopped || sessionChannels != null || sessionKey == null) {
            return;
        }
        sessionChannels = new WirelessSessionChannels(
            identity,
            new WirelessSessionChannels.Callback() {
            @Override
            public void onLog(String message) {
                callback.onLog(message);
            }

            @Override
            public void onState(int stage, String state, String detail) {
                callback.onState(stage, state, detail, pin);
            }

            @Override
            public void onDisconnected(String reason) {
                disconnected(reason);
            }
            }
        );
        sessionChannels.start(phoneIp, sessionKey);
    }

    private static String phoneAddress(String connectionInfo, String fallback) {
        if (connectionInfo == null || connectionInfo.isEmpty()) {
            return fallback;
        }
        int colon = connectionInfo.indexOf(':');
        String value = colon < 0 ? connectionInfo : connectionInfo.substring(0, colon);
        return value.isEmpty() ? fallback : value;
    }

    private void startBluetooth() {
        if (stopped || bleStarted) {
            return;
        }
        bleStarted = true;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("蓝牙未开启");
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            fail("此设备不支持 BLE Peripheral 广播");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            fail("系统没有提供 BLE advertiser");
            return;
        }
        try {
            gattServer = manager.openGattServer(context, gattCallback);
            if (gattServer == null) {
                fail("无法创建 BLE GATT server");
                return;
            }
        } catch (SecurityException error) {
            fail("创建 GATT server 的权限被拒绝：" + error.getMessage());
            return;
        }

        BluetoothGattService service = new BluetoothGattService(
            IccoaProtocol.SHARE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        );
        BluetoothGattCharacteristic clientCharacteristic = new BluetoothGattCharacteristic(
            IccoaProtocol.CLIENT_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        serverCharacteristic = new BluetoothGattCharacteristic(
            IccoaProtocol.SERVER_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        );
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
            IccoaProtocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        serverCharacteristic.addDescriptor(cccd);
        service.addCharacteristic(clientCharacteristic);
        service.addCharacteristic(serverCharacteristic);
        try {
            if (!gattServer.addService(service)) {
                fail("添加 ICCOA GATT service 失败");
            }
        } catch (SecurityException error) {
            fail("注册 ICCOA GATT service 的权限被拒绝：" + error.getMessage());
        }
    }

    private void startAdvertising() {
        if (stopped || advertiser == null || identity == null) { return; }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build();
        AdvertiseData advertisement = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(new ParcelUuid(IccoaProtocol.ADVERTISEMENT_UUID))
            .addServiceData(
                new ParcelUuid(IccoaProtocol.PRIMARY_DATA_UUID),
                IccoaProtocol.primaryData(identity)
            )
            .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(
                new ParcelUuid(IccoaProtocol.CAR_NAME_DATA_UUID),
                IccoaProtocol.carNameData()
            )
            .build();
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                if (stopped) { return; }
                callback.onLog("ICCOA BLE 广播已启动：FCFB + INFO1 + INFO2");
                callback.onState(2, "等待手机", ssid + " · " + frequency + " MHz", pin);
            }

            @Override
            public void onStartFailure(int errorCode) {
                fail("BLE 广播启动失败：" + advertiseError(errorCode));
            }
        };
        try {
            advertiser.startAdvertising(settings, advertisement, scanResponse, advertiseCallback);
        } catch (SecurityException error) {
            fail("BLE 广播权限被拒绝：" + error.getMessage());
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            main.post(() -> {
                if (stopped) { return; }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("GATT service 注册失败：" + status);
                    return;
                }
                callback.onLog("ICCOA GATT service 已注册");
                main.post(WirelessCarLinkEngine.this::startAdvertising);

            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            main.post(() -> {
                if (stopped) { return; }
                String key = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.put(key, device);
                    negotiatedMtus.put(key, DEFAULT_MTU);
                    callback.onLog("手机已连接 BLE GATT：" + maskedAddress(key));
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(key);
                    negotiatedMtus.remove(key);
                    subscribedDevices.remove(key);
                    pendingServerInfo.remove(key);
                    indicatingDevices.remove(key);
                    preparedWrites.remove(key);
                    callback.onLog("BLE GATT 已断开：" + maskedAddress(key));
                }

            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            main.post(() -> {
                if (stopped) { return; }
                negotiatedMtus.put(device.getAddress(), mtu);
                callback.onLog("BLE MTU：" + mtu);
                trySendServerInfo(device);

            });
        }

        @Override
        public void onCharacteristicWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value
        ) {
            main.post(() -> {
                if (stopped) { return; }
                if (!IccoaProtocol.CLIENT_INFO_UUID.equals(characteristic.getUuid())) {
                    respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null, responseNeeded);
                    return;
                }
                String key = device.getAddress();
                if (preparedWrite) {
                    ByteArrayOutputStream output = preparedWrites.computeIfAbsent(
                        key,
                        ignored -> new ByteArrayOutputStream()
                    );
                    synchronized (output) {
                        if (offset != output.size()) {
                            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null, responseNeeded);
                            return;
                        }
                        output.write(value, 0, value.length);
                    }
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value, responseNeeded);
                    return;
                }
                if (offset != 0) {
                    respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null, responseNeeded);
                    return;
                }
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value, responseNeeded);
                processClientInfo(device, value);

            });
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            main.post(() -> {
                if (stopped) { return; }
                ByteArrayOutputStream output = preparedWrites.remove(device.getAddress());
                if (!execute || output == null) {
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null, true);
                    return;
                }
                byte[] value;
                synchronized (output) {
                    value = output.toByteArray();
                }
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null, true);
                processClientInfo(device, value);

            });
        }

        @Override
        public void onDescriptorWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattDescriptor descriptor,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value
        ) {
            main.post(() -> {
                if (stopped) { return; }
                if (!IccoaProtocol.CCCD_UUID.equals(descriptor.getUuid())) {
                    respondDescriptor(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, responseNeeded);
                    return;
                }
                boolean enabled = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    || Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (enabled) {
                    subscribedDevices.add(device.getAddress());
                    callback.onLog("手机已订阅 Server Info indication");
                } else {
                    subscribedDevices.remove(device.getAddress());
                }
                respondDescriptor(device, requestId, BluetoothGatt.GATT_SUCCESS, responseNeeded);
                trySendServerInfo(device);

            });
        }

        @Override
        public void onDescriptorReadRequest(
            BluetoothDevice device,
            int requestId,
            int offset,
            BluetoothGattDescriptor descriptor
        ) {
            main.post(() -> {
                if (stopped) { return; }
                if (!IccoaProtocol.CCCD_UUID.equals(descriptor.getUuid())) {
                    respondDescriptor(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, true);
                    return;
                }
                byte[] value = subscribedDevices.contains(device.getAddress())
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value, true);

            });
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            main.post(() -> {
                if (stopped) { return; }
                String key = device.getAddress();
                indicatingDevices.remove(key);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingServerInfo.remove(key);
                    callback.onLog("手机已确认 Server Info indication");
                } else {
                    callback.onLog("Server Info indication 失败：" + status + "，准备重试");
                    main.postDelayed(() -> trySendServerInfo(device), 500L);
                }

            });
        }
    };


    private void processClientInfo(BluetoothDevice device, byte[] value) {
        if (stopped || sessionChannels != null || activeAuthClient != null) { return; }
        String key = device.getAddress();
        pendingServerInfo.add(key);
        String label = IccoaProtocol.clientLabel(value);
        String clientPin = IccoaProtocol.clientPin(value);
        if (!clientPin.isEmpty()) {
            pin = clientPin;
            callback.onLog("已采用 Client Info 提供的认证码（长度 " + clientPin.length() + "）");
        }
        callback.onLog("收到 Client Info：" + label + "，" + value.length + " 字节");
        callback.onState(3, "手机已发现车机", label + " · 正在下发网络参数", pin);
        trySendServerInfo(device);
    }

    private void trySendServerInfo(BluetoothDevice device) {
        if (stopped || gattServer == null || serverCharacteristic == null) { return; }
        String key = device.getAddress();
        if (!pendingServerInfo.contains(key)
            || indicatingDevices.contains(key)) {
            return;
        }
        byte[] payload = IccoaProtocol.serverInfo(ssid, passphrase, frequency);
        int mtu = negotiatedMtus.getOrDefault(key, DEFAULT_MTU);
        if (payload.length > mtu - 3) {
            callback.onLog("等待更大的 BLE MTU：当前 " + mtu + "，需要 " + (payload.length + 3));
            return;
        }
        boolean sent;
        indicatingDevices.add(key);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gattServer.notifyCharacteristicChanged(
                    device,
                    serverCharacteristic,
                    true,
                    payload
                );
                sent = result == BluetoothStatusCodes.SUCCESS;
            } else {
                serverCharacteristic.setValue(payload);
                sent = gattServer.notifyCharacteristicChanged(device, serverCharacteristic, true);
            }
        } catch (SecurityException error) {
            indicatingDevices.remove(key);
            fail("发送 Server Info 的蓝牙权限被拒绝：" + error.getMessage());
            return;
        }
        if (!sent) {
            indicatingDevices.remove(key);
            callback.onLog("Server Info indication 暂未发送，等待重试");
            main.postDelayed(() -> trySendServerInfo(device), 500L);
            return;
        }
        callback.onLog("Server Info indication 已排队，等待手机确认");
    }

    private void respond(
        BluetoothDevice device,
        int requestId,
        int status,
        int offset,
        byte[] value,
        boolean responseNeeded
    ) {
        if (responseNeeded && gattServer != null) {
            try {
                gattServer.sendResponse(device, requestId, status, offset, value);
            } catch (SecurityException error) {
                fail("回复 GATT 请求的蓝牙权限被拒绝：" + error.getMessage());
            }
        }
    }

    private void respondDescriptor(
        BluetoothDevice device,
        int requestId,
        int status,
        boolean responseNeeded
    ) {
        if (responseNeeded && gattServer != null) {
            try {
                gattServer.sendResponse(device, requestId, status, 0, null);
            } catch (SecurityException error) {
                fail("回复 GATT 描述符的蓝牙权限被拒绝：" + error.getMessage());
            }
        }
    }

    private void stopBluetooth() {
        BluetoothLeAdvertiser value = advertiser;
        AdvertiseCallback callbackValue = advertiseCallback;
        advertiser = null;
        advertiseCallback = null;
        if (value != null && callbackValue != null) {
            try {
                value.stopAdvertising(callbackValue);
            } catch (SecurityException ignored) {
            }
        }
        BluetoothGattServer server = gattServer;
        gattServer = null;
        serverCharacteristic = null;
        if (server != null) {
            try {
                for (BluetoothDevice device : connectedDevices.values()) {
                    server.cancelConnection(device);
                }
                server.clearServices();
                server.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    private void closeAuthListener() {
        ServerSocket listener = authListener;
        authListener = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void disconnected(String reason) {
        if (!stopped && disconnected.compareAndSet(false, true)) {
            callback.onDisconnected(reason);
        }
    }

    private void fail(String message) {
        if (!stopped) {
            callback.onFatal(message == null ? "未知错误" : message);
        }
    }

    private String maskedAddress(String value) {
        return value.length() > 5 ? "**:**:**:**:" + value.substring(value.length() - 5) : value;
    }

    private String p2pReason(int reason) {
        if (reason == WifiP2pManager.BUSY) {
            return "BUSY";
        }
        if (reason == WifiP2pManager.ERROR) {
            return "ERROR";
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return "P2P_UNSUPPORTED";
        }
        return Integer.toString(reason);
    }

    private String advertiseError(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "DATA_TOO_LARGE";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "TOO_MANY_ADVERTISERS";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            default:
                return Integer.toString(errorCode);
        }
    }

    private static final class QuietActionListener implements WifiP2pManager.ActionListener {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason) {
        }
    }
}
