package wangfei.ble;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.UUID;

public class BaseBle extends BluetoothGattCallback {
    protected UUID UUID_SERVICE;
    protected UUID UUID_WRITE;
    protected UUID UUID_NOTIFY;
    protected UUID UUID_DESC;

    protected byte[] mDescripterType;
    protected boolean mIsConnectAllTheTime = false;
    protected Application mCtx;
    protected BluetoothAdapter mBtAdapter;
    protected String mAddress;
    public OnStateListener mOnStateListener;
    public OnScanListener mOnBleDevListListener;
    public OnReceiveListener mOnDatasListener;//接受数据的监听
    protected byte[] tempCmd2 = null;
    protected BluetoothGattCharacteristic mWriteChar;
    protected BluetoothGatt mBluetoothGatt;
    protected byte[] tempCmd1 = new byte[20];
    protected UUID[] scanUUIDs = null;
    protected byte[] cmdFirstConnet = null;//连接数据
    private ArrayList<BleBean> mScans = new ArrayList<BleBean>();//扫描ble结果

    /**
     * @param app 上下文
     */
    public BaseBle(Application app) {
        mCtx = app;
        final BluetoothManager bluetoothManager = (BluetoothManager)
                app.getSystemService(Context.BLUETOOTH_SERVICE);
        mBtAdapter = bluetoothManager.getAdapter();
    }

    /**
     * @param service             服务的uuid
     * @param write               传递消息的uuid
     * @param notify              唤醒的uuid
     * @param descripter          android特有的订阅uuid
     * @param descripterType      BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  或者  BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
     * @param isConnectAllTheTime 是否保持一直连接
     */
    public void setBaseDatas(UUID service, UUID write, UUID notify, UUID descripter, byte[] descripterType, boolean isConnectAllTheTime) {
        UUID_SERVICE = service;
        UUID_WRITE = write;
        UUID_NOTIFY = notify;
        UUID_DESC = descripter;
        scanUUIDs = new UUID[]{UUID_SERVICE};
        mDescripterType = descripterType;
        mIsConnectAllTheTime = isConnectAllTheTime;
    }

    public boolean scanStart() {
        mScans.clear();//清空扫描记录列表
        return mBtAdapter != null && mBtAdapter.startLeScan(scanUUIDs, m18LeScanCallback);
    }

    public boolean scanStart(UUID[] scanUUIDs) {
        mScans.clear();//清空扫描记录列表
        return mBtAdapter != null && mBtAdapter.startLeScan(scanUUIDs, m18LeScanCallback);
    }

    public void scanStop() {
        if (mBtAdapter != null)//华为:G7-Ul20;CHM-00;G7-TL00;蓝牙4.0,会出现空指针异常
            mBtAdapter.stopLeScan(m18LeScanCallback);
    }

    public boolean isSending() {
        return tempCmd2 != null;
    }

    public synchronized boolean writeChar(byte[] tempDatas) {
        if (mWriteChar == null || mBluetoothGatt == null || tempDatas == null)
            return false;
        if (tempDatas.length <= 20) {
            tempCmd2 = null;
            return writeCharInner(tempDatas);
        } else {
            System.arraycopy(tempDatas, 0, tempCmd1, 0, 20);
            tempCmd2 = new byte[tempDatas.length - 20];
            System.arraycopy(tempDatas, 20, tempCmd2, 0, tempDatas.length - 20);
            return writeCharInner(tempCmd1);
        }
    }


    private synchronized boolean writeCharInner(byte[] bytes) {//写特征值
        Log.d("wangfeibles", "--->" + BleTool.bytes16ToString(bytes) + "===" + BleTool.bytes16ToHexStrings(bytes));
        return mWriteChar.setValue(bytes) && mBluetoothGatt.writeCharacteristic(mWriteChar);
    }

    public int connect(String address) {
        mAddress = address;
        close();
        if (mBtAdapter == null) {
            return OnStateListener.BLE_NO_Adapter;
        }
        final BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
        if (device == null) {
            return OnStateListener.BLE_NO_Device;
        }
        mBluetoothGatt = device.connectGatt(mCtx, false, this);//第一步:连接gatt:获取gatt管道
        return OnStateListener.BLE_OK_START;
    }

    public void close() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            if (mBluetoothGatt != null) {
                mBluetoothGatt.close();
                if (mBluetoothGatt != null)
                    mBluetoothGatt = null;
            }
        }
        if (mBtAdapter != null) {
            if (mAddress != null && !mAddress.equals("")) {
                BluetoothDevice device = mBtAdapter.getRemoteDevice(mAddress);
                if (device != null) {
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        try {
                            BleTool.removeBond(BluetoothDevice.class, device);//适配魅族某款手机
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        Log.d("wangfeibles", "status__" + status + "newState__" + newState);
        if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
            gatt.discoverServices();//第二步:gatt连接成功,发现服务
        } else {
            if (mIsConnectAllTheTime)
                connect(mAddress);
        }
        if (mOnStateListener != null)
            mOnStateListener.onStateChange(10000 + status + newState);
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (mOnStateListener != null)
            mOnStateListener.onStateChange(20000 + status);
        if (status == 0) {//第三步:发现服务成功;获取服务,订阅特征值
            BluetoothGattService service = gatt.getService(UUID_SERVICE);
            mWriteChar = service.getCharacteristic(UUID_WRITE);
            BluetoothGattCharacteristic mIndicateChar = service.getCharacteristic(UUID_NOTIFY);
            gatt.setCharacteristicNotification(mIndicateChar, true);//写入特征值改变时,通知
            BluetoothGattDescriptor descriptor = mIndicateChar.getDescriptor(UUID_DESC);//获取修饰
            descriptor.setValue(mDescripterType);//修饰配置
//            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);//修饰配置
            gatt.writeDescriptor(descriptor);
        } else {
            if (mIsConnectAllTheTime)
                connect(mAddress);
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        if (mOnStateListener != null)
            mOnStateListener.onStateChange(30000 + status);
        if (status == 0) {//第四步:订阅特征值成功;发送数据;交换随即密钥
            if (cmdFirstConnet != null)
                writeChar(cmdFirstConnet);
        } else {
            if (mIsConnectAllTheTime)
                connect(mAddress);
        }
    }

    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
//        if (mOnStateListener != null)
//            mOnStateListener.onStateChange(30000 + status);
        if (status == 0) {//第六步:写入第一包数据后睡眠,发送第二包数据
            if (tempCmd2 != null) {
//                try {
//                    Thread.sleep(50);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
                writeChar(tempCmd2);
            }
        }
    }

    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        Log.d("wangfeibles", "<---" + BleTool.bytes16ToString(characteristic.getValue()) + "===" + BleTool.bytes16ToHexStrings(characteristic.getValue()));
        if (mOnDatasListener != null)
            mOnDatasListener.onReceive(characteristic.getValue());
    }

    private BluetoothAdapter.LeScanCallback m18LeScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (mOnBleDevListListener == null)
                return;
            byte[] macTemp = new byte[9];
            BleTool.parseRfDeviceScanRecord(bytes, macTemp);
            BleBean bean = new BleBean(device.getName(), device.getAddress(), rssi, macTemp);
            if (!BleTool.containBytes(macTemp, mScans)) {//是否为扫描记录列表中的设备
                mScans.add(bean);
                if (mOnBleDevListListener != null) {
                    mOnBleDevListListener.onNewBleBean(bean);
                    mOnBleDevListListener.onBleBeanList(mScans);
                }
            }
        }
    };


    //    private ScanCallback m21ScanCallback = new ScanCallback() {
//        @Override
//        public void onScanResult(int callbackType, ScanResult result) {
//            byte[] scanRecord = result.getScanRecord().getBytes();
//            BluetoothDevice device = result.getDevice();
//            int rssi = result.getRssi();
////            foundNewDevice(scanRecord, device, rssi);
//        }
//    };

    public int connect(String address, byte[] cmd) {
        cmdFirstConnet = cmd;
        return connect(address);
    }

    public int connectByMac(String mac, byte[] cmd) {
        String addressFromMac = BleTool.getAddressFromMac(BleTool.macStr2Bytes(mac), mScans);
        if (addressFromMac == null) {
            return OnStateListener.BLE_NO_SERVICE;
        }
        cmdFirstConnet = cmd;
        return connect(addressFromMac);
    }

    public void setOnBleListListener(OnScanListener asdaasd) {
        mOnBleDevListListener = asdaasd;
    }

    public void setOnStateListener(OnStateListener aaaa) {
        mOnStateListener = aaaa;
    }

    public void setOnDatasListener(OnReceiveListener dddddd) {
        mOnDatasListener = dddddd;
    }

    public BluetoothAdapter getAdapter() {
        return mBtAdapter;
    }
}
