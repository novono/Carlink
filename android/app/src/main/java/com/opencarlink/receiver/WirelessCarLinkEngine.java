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
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

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

final class WirelessCarLinkEngine {
    interface Callback {
        void onState(int stage, String state, String detail, String pin);

        void onLog(String message);

        void onFatal(String message);
    }

    private static final int AUTH_PORT = 57209;
    private static final int DEFAULT_MTU = 23;

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final String requestedSsid = IccoaProtocol.randomSsid();
    private final String requestedPassphrase = IccoaProtocol.randomPassphrase();
    private volatile String pin = IccoaProtocol.randomPin();
    private final IccoaProtocol.Identity identity;
    private final Map<String, ByteArrayOutputStream> preparedWrites = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> negotiatedMtus = new ConcurrentHashMap<>();
    private final Set<String> subscribedDevices = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingServerInfo = ConcurrentHashMap.newKeySet();
    private final Set<String> indicatingDevices = ConcurrentHashMap.newKeySet();

    private WifiP2pManager wifiManager;
    private WifiP2pManager.Channel wifiChannel;
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
        stopped = true;
        stopBluetooth();
        closeAuthListener();
        WirelessSessionChannels channels = sessionChannels;
        sessionChannels = null;
        if (channels != null) {
            channels.stop();
        }
        io.shutdownNow();
        BroadcastReceiver receiver = wifiReceiver;
        wifiReceiver = null;
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (wifiManager != null && wifiChannel != null) {
            try {
                wifiManager.removeGroup(wifiChannel, new QuietActionListener());
            } catch (SecurityException ignored) {
            }
        }
        callback.onLog("无线资源已释放");
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
        wifiManager.removeGroup(wifiChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                createGroup();
            }

            @Override
            public void onFailure(int reason) {
                createGroup();
            }
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
        try {
            wifiManager.requestGroupInfo(wifiChannel, this::onGroupInfo);
        } catch (SecurityException error) {
            fail("读取 P2P group info 被拒绝：" + error.getMessage());
        }
    }

    private synchronized void onGroupInfo(WifiP2pGroup value) {
        if (stopped || value == null || !value.isGroupOwner()) {
            return;
        }
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
                authListener = listener;
                callback.onLog("AUTH 已监听 TCP " + AUTH_PORT);
                main.post(this::startBluetooth);
                while (!stopped) {
                    try {
                        Socket client = listener.accept();
                        configureClient(client);
                        io.execute(() -> readAuthClient(client));
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
        callback.onState(4, "正在认证 OPPO 手机", peer + ":" + client.getPort(), pin);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        IccoaAuthSession auth = new IccoaAuthSession(pin, new AndroidAuthStore(context));
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
                            String detail = auth.connectionInfo().isEmpty()
                                ? peer
                                : auth.connectionInfo();
                            callback.onState(5, "无线认证成功", detail, pin);
                            callback.onLog("AUTH_CONFIRM 已完成；正在建立 CONTROL/RTSP/RTP");
                            startSessionChannels(phoneAddress(auth.connectionInfo(), peer), auth.sessionKey());
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
            callback.onLog("AUTH 连接关闭，累计 " + total + " 字节");
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
                callback.onLog("ICCOA BLE 广播已启动：FCFB + INFO1 + INFO2");
                callback.onState(2, "等待 OPPO 手机", ssid + " · " + frequency + " MHz", pin);
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT service 注册失败：" + status);
                return;
            }
            callback.onLog("ICCOA GATT service 已注册");
            main.post(WirelessCarLinkEngine.this::startAdvertising);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
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
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            negotiatedMtus.put(device.getAddress(), mtu);
            callback.onLog("BLE MTU：" + mtu);
            trySendServerInfo(device);
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
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
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
        }

        @Override
        public void onDescriptorReadRequest(
            BluetoothDevice device,
            int requestId,
            int offset,
            BluetoothGattDescriptor descriptor
        ) {
            if (!IccoaProtocol.CCCD_UUID.equals(descriptor.getUuid())) {
                respondDescriptor(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, true);
                return;
            }
            byte[] value = subscribedDevices.contains(device.getAddress())
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value, true);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            String key = device.getAddress();
            indicatingDevices.remove(key);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingServerInfo.remove(key);
                callback.onLog("手机已确认 Server Info indication");
            } else {
                callback.onLog("Server Info indication 失败：" + status + "，准备重试");
                main.postDelayed(() -> trySendServerInfo(device), 500L);
            }
        }
    };

    private void processClientInfo(BluetoothDevice device, byte[] value) {
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
        if (server != null) {
            try {
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
